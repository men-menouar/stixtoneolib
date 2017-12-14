package com.kodekutters.neo4j

import com.kodekutters.stix.{Bundle, _}
import com.typesafe.scalalogging.Logger

import scala.language.{implicitConversions, postfixOps}

/**
  * loads Stix-2.0 objects, SDO, SRO and associated data types into a Neo4j graph database
  *
  * @author R. Wathelet June 2017, revised December 2017
  * @param dbDir the neo4j graph database directory name of an existing database or where a new one will be created
  */
class Neo4jLoader(dbDir: String) {

  private val logger = Logger[Neo4jLoader]

  // initialize the neo4j db services
  Neo4jDbService.init(dbDir)

  // the nodes maker for creating nodes and their embedded relations
  val nodesMaker = new NodesMaker()
  // the relations maker for creating relations
  val relsMaker = new RelationsMaker()

  /**
    * load a bundle of Stix objects to a Neo4j database
    *
    * @param bundle the bundle of Stix objects
    */
  def loadIntoNeo4j(bundle: Bundle) = {
    // all nodes and their internal relations are created first
    bundle.objects.foreach(nodesMaker.createNodes(_))
    // all SRO and relations that depends on nodes are created after the nodes
    bundle.objects.foreach(relsMaker.createRelations(_))
  }

  /**
    * load a Stix object to a Neo4j database
    *
    * @param stix the Stix object
    */
  def loadIntoNeo4j(stix: StixObj) = {
    // first create the nodes associated with the stix and any internal relations
    nodesMaker.createNodes(stix)
    // create the relations that depends on nodes
    relsMaker.createRelations(stix)
  }

  /**
    * close the Neo4j database service
    */
  def close(): Unit = {
    Neo4jDbService.closeAll()
  }

}