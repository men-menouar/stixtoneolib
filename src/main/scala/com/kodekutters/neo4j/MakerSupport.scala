package com.kodekutters.neo4j

import java.util.UUID

import com.kodekutters.stix._
import com.typesafe.scalalogging.Logger
import org.neo4j.graphdb.Label.label
import org.neo4j.graphdb.{Node, RelationshipType}
import play.api.libs.json.Json

import scala.language.implicitConversions
import scala.language.postfixOps

/**
  * embedded nodes and relations creation support
  */
class MakerSupport(neoService: Neo4jDbService) {

  // convenience implicit transformation from a string to a RelationshipType
  implicit def string2relationshipType(x: String): RelationshipType = RelationshipType.withName(x)

  /**
    * convenience method for converting a CustomMap option of custom properties into a json string
    */
  def asJsonString(cust: Option[CustomProps]) = {
    cust match {
      case Some(x) => Json.stringify(Json.toJson[CustomProps](x))
      case None => ""
    }
  }

  // make an array of unique random id values from the input list
  def toIdArray(dataList: Option[List[Any]]): Array[String] = {
    (for (s <- dataList.getOrElse(List.empty)) yield UUID.randomUUID().toString).toArray
  }

  // make an array of id strings from the list of Identifier
  def toIdStringArray(dataList: Option[List[Identifier]]): Array[String] = {
    (for (s <- dataList.getOrElse(List.empty)) yield s.toString()).toArray
  }

  // the Neo4j :LABEL and :TYPE cannot deal with "-", so clean and replace with "_"
  def asCleanLabel(s: String) = s.replace(",", " ").replace(":", " ").replace("\'", " ").
    replace(";", " ").replace("\"", "").replace("\\", "").replace(".", " ").
    replace("\n", "").replace("\r", "").replace("-", "_")

  /**
    * create the marking definition node and its relationship
    * @param sourceNode the parent node
    * @param definition the marking object
    * @param definition_id the marking object id
    */
  def createMarkingDef(sourceNode: Node, definition: MarkingObject, definition_id: String)(implicit logger: Logger) = {
    val mark: String = definition match {
      case s: StatementMarking => s.statement
      case s: TPLMarking => s.tlp.value
      case _ => ""
    }
    val markObjNodeOpt = neoService.transaction {
      val node = neoService.graphDB.createNode(label("marking_object_refs"))
      node.setProperty("marking_id", definition_id)
      node.setProperty("marking", mark)
      node
    }
    markObjNodeOpt match {
      case Some(markObjNode) =>
        neoService.transaction {
          sourceNode.createRelationshipTo(markObjNode, "HAS_MARKING_OBJECT")
        }.getOrElse(logger.error("could not process marking_object_refs relation: " + definition_id))

      case None => logger.error("could not create marking_object_refs definition_id: " + definition_id)
    }
  }

  /**
    * create the kill_chain_phases nodes and relationships
    * @param sourceNode the parent node
    * @param kill_chain_phasesOpt the possible list of kill_chain objects
    * @param ids the ids representing the kill_chain object
    */
  def createKillPhases(sourceNode: Node, kill_chain_phasesOpt: Option[List[KillChainPhase]], ids: Array[String])(implicit logger: Logger) = {
    kill_chain_phasesOpt.foreach(kill_chain_phases => {
      for ((kp, i) <- kill_chain_phases.zipWithIndex) {
        val stixNodeOpt = neoService.transaction {
          val node = neoService.graphDB.createNode(label(asCleanLabel(kp.`type`)))
          node.setProperty("kill_chain_phase_id", ids(i))
          node.setProperty("kill_chain_name", kp.kill_chain_name)
          node.setProperty("phase_name", kp.phase_name)
          node
        }
        stixNodeOpt match {
          case Some(stixNode) =>
            neoService.transaction {
              sourceNode.createRelationshipTo(stixNode, "HAS_KILL_CHAIN_PHASE")
            }.getOrElse(logger.error("could not process relation: HAS_KILL_CHAIN_PHASE"))

          case None => logger.error("could not create node kill_chain_phase: " + kp.toString())
        }
      }
    })
  }

  /**
    * create the external_references nodes and relationships
    * @param idString the parent node id
    * @param external_referencesOpt the possible list of ExternalReference objects
    * @param ids the ids representing the ExternalReferences
    */
  def createExternRefs(idString: String, external_referencesOpt: Option[List[ExternalReference]], ids: Array[String])(implicit logger: Logger): Unit = {
    val sourceNodeOpt = neoService.transaction {
      neoService.idIndex.get("id", idString).getSingle
    }
    sourceNodeOpt match {
      case Some(sourceNode) => createExternRefs(sourceNode, external_referencesOpt, ids)
      case None => logger.error("could not create node external_reference for: " + idString)
    }
  }

  /**
    * create the external_references nodes and relationships
    * @param sourceNode the parent node
    * @param external_referencesOpt the possible list of ExternalReference
    * @param ids the ids representing the ExternalReferences
    */
  def createExternRefs(sourceNode: Node, external_referencesOpt: Option[List[ExternalReference]], ids: Array[String])(implicit logger: Logger): Unit = {
    external_referencesOpt.foreach(external_references => {
      for ((extRef, i) <- external_references.zipWithIndex) {
        val hashes_ids: Map[String, String] = (for (s <- extRef.hashes.getOrElse(Map.empty).keySet) yield s -> UUID.randomUUID().toString).toMap
        val stixNodeOpt = neoService.transaction {
          val node = neoService.graphDB.createNode(label(asCleanLabel(extRef.`type`)))
          node.setProperty("external_reference_id", ids(i))
          node.setProperty("source_name", extRef.source_name)
          node.setProperty("description", extRef.description.getOrElse(""))
          node.setProperty("external_id", extRef.external_id.getOrElse(""))
          node.setProperty("url", extRef.url.getOrElse(""))
          node.setProperty("hashes", hashes_ids.values.toArray)
          node
        }
        stixNodeOpt match {
          case Some(stixNode) =>
            createHashes(stixNode, extRef.hashes, hashes_ids)
            neoService.transaction {
              sourceNode.createRelationshipTo(stixNode, "HAS_EXTERNAL_REF")
            }.getOrElse(logger.error("could not process relation: HAS_EXTERNAL_REF"))

          case None => logger.error("could not create node external_reference: " + extRef.toString)
        }
      }
    })
  }

  /**
    * create the granular_markings nodes and relationships
    * @param idString the parent node id
    * @param granular_markingsOpt the possible list of GranularMarking
    * @param ids the ids representing the GranularMarking
    */
  def createGranulars(idString: String, granular_markingsOpt: Option[List[GranularMarking]], ids: Array[String])(implicit logger: Logger): Unit = {
    val sourceNodeOpt = neoService.transaction {
      neoService.idIndex.get("id", idString).getSingle
    }
    sourceNodeOpt match {
      case Some(sourceNode) => createGranulars(sourceNode, granular_markingsOpt, ids)
      case None => logger.error("could not create node granular_markings for: " + idString)
    }
  }

  /**
    * create the granular_markings nodes and relationships
    * @param sourceNode the parent node
    * @param granular_markingsOpt the possible list of GranularMarking
    * @param ids the ids representing the GranularMarking
    */
  def createGranulars(sourceNode: Node, granular_markingsOpt: Option[List[GranularMarking]], ids: Array[String])(implicit logger: Logger): Unit = {
    granular_markingsOpt.foreach(granular_markings => {
      for ((gra, i) <- granular_markings.zipWithIndex) {
        val stixNodeOpt = neoService.transaction {
          val node = neoService.graphDB.createNode(label(asCleanLabel(gra.`type`)))
          node.setProperty("granular_marking_id", ids(i))
          node.setProperty("selectors", gra.selectors.toArray)
          node.setProperty("marking_ref", gra.marking_ref.getOrElse(""))
          node.setProperty("lang", gra.lang.getOrElse(""))
          node
        }
        stixNodeOpt match {
          case Some(stixNode) =>
            neoService.transaction {
              sourceNode.createRelationshipTo(stixNode, "HAS_GRANULAR_MARKING")
            }.getOrElse(logger.error("could not process relation: HAS_GRANULAR_MARKING"))

          case None => logger.error("could not create node granular_marking: " + gra.toString())
        }
      }
    })
  }

  /**
    * create relations between the idString and the list of object_refs SDO id
    * @param idString the parent node id
    * @param object_refs the possible list of Identifier
    * @param relName the relation name
    */
  def createRelToObjRef(idString: String, object_refs: Option[List[Identifier]], relName: String)(implicit logger: Logger) = {
    for (s <- object_refs.getOrElse(List.empty)) {
      neoService.transaction {
        val sourceNode = neoService.idIndex.get("id", idString).getSingle
        val targetNode = neoService.idIndex.get("id", s.toString()).getSingle
        sourceNode.createRelationshipTo(targetNode, relName)
      }.getOrElse(logger.error("could not process " + relName + " relation from: " + idString + " to: " + s.toString()))
    }
  }

  /**
    * create a CREATED_BY relation between the sourceId and the target
    * @param sourceId the parent node id
    * @param tgtOpt the possible target Identifier
    */
  def createdByRel(sourceId: String, tgtOpt: Option[Identifier])(implicit logger: Logger) = {
    tgtOpt.map(tgt => {
      neoService.transaction {
        val sourceNode = neoService.idIndex.get("id", sourceId).getSingle
        val targetNode = neoService.idIndex.get("id", tgt.toString()).getSingle
        sourceNode.createRelationshipTo(targetNode, "CREATED_BY")
      }.getOrElse(logger.error("could not process CREATED_BY relation from: " + sourceId + " to: " + tgt.toString()))
    })
  }

  def createLangContents(sourceNode: Node, contents: Map[String, Map[String, String]], ids: Map[String, String])(implicit logger: Logger) = {
    for ((k, obs) <- contents) {
      val obs_contents_ids: Map[String, String] = (for (s <- obs.keySet) yield s -> UUID.randomUUID().toString).toMap
      val tgtNodeOpt = neoService.transaction {
        val node = neoService.graphDB.createNode(label("contents"))
        node.setProperty("contents_id", ids(k))
        node.setProperty(k, obs_contents_ids.values.toArray)
        node
      }
      tgtNodeOpt match {
        case Some(tgtNode) =>
          createTranslations(tgtNode, obs, obs_contents_ids)
          neoService.transaction {
            sourceNode.createRelationshipTo(tgtNode, "HAS_CONTENTS")
          }.getOrElse(logger.error("could not process language HAS_CONTENTS relation"))

        case None => logger.error("could not create node language contents")
      }
    }
  }

  private def createTranslations(sourceNode: Node, translations: Map[String, String], ids: Map[String, String])(implicit logger: Logger) = {
    for ((k, obs) <- translations) {
      val tgtNodeOpt = neoService.transaction {
        val node = neoService.graphDB.createNode(label("translations"))
        node.setProperty("translations_id", ids(k))
        node.setProperty(k, obs)
        node
      }
      tgtNodeOpt match {
        case Some(tgtNode) =>
          neoService.transaction {
            sourceNode.createRelationshipTo(tgtNode, "HAS_TRANSLATION")
          }.getOrElse(logger.error("could not process language HAS_TRANSLATION relation"))

        case None => logger.error("could not create node language translations")
      }
    }
  }

  // create the hashes objects and their relationship to the theNode
  def createHashes(theNode: Node, hashesOpt: Option[Map[String, String]], ids: Map[String, String])(implicit logger: Logger) = {
    hashesOpt.foreach(hashes =>
      for ((k, obs) <- hashes) {
        val hashNodeOpt = neoService.transaction {
          val node = neoService.graphDB.createNode(label("hashes"))
          node.setProperty("hash_id", ids(k))
          node.setProperty(k, obs)
          node
        }
        hashNodeOpt match {
          case Some(hashNode) =>
            neoService.transaction {
              theNode.createRelationshipTo(hashNode, "HAS_HASHES")
            }.getOrElse(logger.error("could not process language HAS_HASHES relation"))

          case None => logger.error("could not create node hashes")
        }
      }
    )
  }

}
