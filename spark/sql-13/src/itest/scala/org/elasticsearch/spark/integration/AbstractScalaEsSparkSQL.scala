/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.spark.integration;

import java.{util => ju, lang => jl}

import java.sql.Timestamp
import java.util.concurrent.TimeUnit
import scala.collection.JavaConversions.propertiesAsScalaMap
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.Row
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.types.TimestampType
import org.elasticsearch.hadoop.mr.RestUtils
import org.elasticsearch.hadoop.util.TestSettings
import org.elasticsearch.hadoop.util.TestUtils
import org.elasticsearch.spark._
import org.elasticsearch.spark.sql._
import org.elasticsearch.spark.sql.sqlContextFunctions
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.is
import org.hamcrest.Matchers.not
import org.junit.AfterClass
import org.junit.Assert._
import org.junit.Assume._
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import org.elasticsearch.hadoop.cfg.ConfigurationOptions._
import org.junit.Test
import javax.xml.bind.DatatypeConverter
import org.apache.spark.sql.catalyst.expressions.GenericRow
import java.util.Arrays


object AbstractScalaEsScalaSparkSQL {
  @transient val conf = new SparkConf().setAll(TestSettings.TESTING_PROPS).setMaster("local").setAppName("estest").set("spark.executor.extraJavaOptions", "-XX:MaxPermSize=256m")
  @transient var cfg: SparkConf = null
  @transient var sc: SparkContext = null
  @transient var sqc: SQLContext = null

  @BeforeClass
  def setup() {
    sc = new SparkContext(conf)
    sqc = new SQLContext(sc)
  }

  @AfterClass
  def cleanup() {
    if (sc != null) {
      sc.stop
      // give jetty time to clean its act up
      Thread.sleep(TimeUnit.SECONDS.toMillis(3))
    }
  }

  @Parameters
  def testParams(): ju.Collection[Array[jl.Object]] = {
    val list = new ju.ArrayList[Array[jl.Object]]()
    list.add(Array("default_", jl.Boolean.FALSE))
    list.add(Array("with_meta_", jl.Boolean.TRUE))
    list
  }
}

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(classOf[Parameterized])
class AbstractScalaEsScalaSparkSQL(prefix: String, readMetadata: jl.Boolean) extends Serializable {

  val sc = AbstractScalaEsScalaSparkSQL.sc
  val sqc = AbstractScalaEsScalaSparkSQL.sqc
  val cfg = Map(ES_READ_METADATA -> readMetadata.toString())

    @Test
    def testBasicRead() {
        val dataFrame = artistsAsDataFrame
        assertTrue(dataFrame.count > 300)
        dataFrame.registerTempTable("datfile")
        println(dataFrame.schema.treeString)
        //dataFrame.take(5).foreach(println)
        val results = sqc.sql("SELECT name FROM datfile WHERE id >=1 AND id <=10")
        //results.take(5).foreach(println)
    }

    @Test
    def testEsDataFrame1Write() {
      val dataFrame = artistsAsDataFrame

      val target = wrapIndex("sparksql-test/scala-basic-write")
      dataFrame.saveToEs(target, cfg)
      assertTrue(RestUtils.exists(target))
      assertThat(RestUtils.get(target + "/_search?"), containsString("345"))
    }

    @Test
    def testEsDataFrame1WriteWithMapping() {
      val dataFrame = artistsAsDataFrame

      val target = wrapIndex("sparksql-test/scala-basic-write-id-mapping")
      val newCfg = collection.mutable.Map(cfg.toSeq: _*) += (ES_MAPPING_ID -> "id", ES_MAPPING_EXCLUDE -> "url")

      dataFrame.saveToEs(target, newCfg)
      assertTrue(RestUtils.exists(target))
      assertThat(RestUtils.get(target + "/_search?"), containsString("345"))
      assertThat(RestUtils.exists(target + "/1"), is(true))
      assertThat(RestUtils.get(target + "/_search?"), not(containsString("url")))
    }

    @Test
    def testEsDataFrame2Read() {
      val target = wrapIndex("sparksql-test/scala-basic-write")

      val dataFrame = sqc.esDF(target, cfg)
      assertTrue(dataFrame.count > 300)
      val schema = dataFrame.schema.treeString
      assertTrue(schema.contains("id: long"))
      assertTrue(schema.contains("name: string"))
      assertTrue(schema.contains("pictures: string"))
      assertTrue(schema.contains("time: long"))
      assertTrue(schema.contains("url: string"))

      //dataFrame.take(5).foreach(println)

      val tempTable = wrapIndex("basicRead")
      dataFrame.registerTempTable(tempTable)
      val nameRDD = sqc.sql("SELECT name FROM " + tempTable + " WHERE id >= 1 AND id <=10")
      nameRDD.take(7).foreach(println)
      assertEquals(10, nameRDD.count)
    }

    @Test
    def testEsDataFrame3WriteWithRichMapping() {
      val input = TestUtils.sampleArtistsDat()
      val data = sc.textFile(input)

      val schema = StructType(Seq(StructField("id", IntegerType, false),
        StructField("name", StringType, false),
        StructField("url", StringType, true),
        StructField("pictures", StringType, true),
        StructField("time", TimestampType, true),
        StructField("nested",
            StructType(Seq(StructField("id", IntegerType, false),
            StructField("name", StringType, false),
            StructField("url", StringType, true),
            StructField("pictures", StringType, true),
            StructField("time", TimestampType, true))), true)))

      val rowRDD = data.map(_.split("\t")).map(r => Row(r(0).toInt, r(1), r(2), r(3), new Timestamp(DatatypeConverter.parseDateTime(r(4)).getTimeInMillis()),
                                Row(r(0).toInt, r(1), r(2), r(3), new Timestamp(DatatypeConverter.parseDateTime(r(4)).getTimeInMillis()))))
      val dataFrame = sqc.createDataFrame(rowRDD, schema)

      val target = wrapIndex("sparksql-test/scala-basic-write-rich-mapping-id-mapping")
      dataFrame.saveToEs(target, Map(ES_MAPPING_ID -> "id"))
      assertTrue(RestUtils.exists(target))
      assertThat(RestUtils.get(target + "/_search?"), containsString("345"))
      assertThat(RestUtils.exists(target + "/1"), is(true))
    }

    @Test
    def testEsDataFrame4ReadRichMapping() {
      val target = wrapIndex("sparksql-test/scala-basic-write-rich-mapping-id-mapping")

      val dataFrame = sqc.esDF(target, cfg)

      assertTrue(dataFrame.count > 300)
      dataFrame.printSchema()
    }

    private def artistsAsDataFrame = {
      val input = TestUtils.sampleArtistsDat()
      val data = sc.textFile(input)

      val schema = StructType(Seq(StructField("id", IntegerType, false),
        StructField("name", StringType, false),
        StructField("url", StringType, true),
        StructField("pictures", StringType, true),
        StructField("time", TimestampType, true)))

      val rowRDD = data.map(_.split("\t")).map(r => Row(r(0).toInt, r(1), r(2), r(3), new Timestamp(DatatypeConverter.parseDateTime(r(4)).getTimeInMillis())))
      val dataFrame = sqc.createDataFrame(rowRDD, schema)
      dataFrame
    }

    @Test
    def testEsDataFrame50ReadAsDataSource() {
      val target = wrapIndex("sparksql-test/scala-basic-write")
      var options = "resource \"" + target + "\""
      if (readMetadata) {
        options = options + " ,read_metadata \"true\""
      }
      val table = wrapIndex("sqlbasicread1")


      val dataFrame = sqc.sql("CREATE TEMPORARY TABLE " + table +
              " USING org.elasticsearch.spark.sql " +
              " OPTIONS (" + options + ")")


      val dsCfg = collection.mutable.Map(cfg.toSeq: _*) += ("path" -> target)
      val dfLoad = sqc.load("org.elasticsearch.spark.sql", dsCfg.toMap)
      println("root data frame")
      dfLoad.printSchema()

      val results = dfLoad.filter(dfLoad("id") >= 1 && dfLoad("id") <= 10)
      println("results data frame")
      results.printSchema()

      val allRDD = sqc.sql("SELECT * FROM " + table + " WHERE id >= 1 AND id <=10")
      println("select all rdd")
      allRDD.printSchema()

      val nameRDD = sqc.sql("SELECT name FROM " + table + " WHERE id >= 1 AND id <=10")
      println("select name rdd")
      nameRDD.printSchema()

      assertEquals(10, nameRDD.count)
      nameRDD.take(7).foreach(println)
    }

    @Test
    def testEsDataFrameReadAsDataSourceWithMetadata() {
      assumeTrue(readMetadata)

      val target = wrapIndex("sparksql-test/scala-basic-write")
      val table = wrapIndex("sqlbasicread2")

      val options = "resource \"" + target + "\", read_metadata \"true\""
      val dataFrame = sqc.sql("CREATE TEMPORARY TABLE " + table +
              " USING org.elasticsearch.spark.sql " +
              " OPTIONS (" + options + ")")

      val allRDD = sqc.sql("SELECT * FROM " + table + " WHERE id >= 1 AND id <=10")
      allRDD.printSchema()
      allRDD.take(7).foreach(println)

      val dsCfg = collection.mutable.Map(cfg.toSeq: _*) += ("path" -> target)
      val dfLoad = sqc.load("org.elasticsearch.spark.sql", dsCfg.toMap)
      dfLoad.show()
    }

    @Test
    def testEsSchemaFromDocsWithDifferentProperties() {
      val target = wrapIndex("spark-test/scala-sql-varcols")
      val table = wrapIndex("sqlvarcol")

      var options = "resource \"" + target + "\""
      if (readMetadata) {
        options = options + " ,read_metadata \"true\""
      }


      val trip1 = Map("reason" -> "business", "airport" -> "SFO")
      val trip2 = Map("participants" -> 5, "airport" -> "OTP")

      sc.makeRDD(Seq(trip1, trip2)).saveToEs(target)

      val dataFrame = sqc.sql("CREATE TEMPORARY TABLE " + table +
              " USING org.elasticsearch.spark.sql " +
              " OPTIONS (" + options + ")")

      val allResults = sqc.sql("SELECT * FROM " + table)
      assertEquals(2, allResults.count())
      allResults.printSchema()

      val filter = sqc.sql("SELECT * FROM " + table + " WHERE airport = 'OTP'")
      assertEquals(1, filter.count())

      val nullColumns = sqc.sql("SELECT reason, airport FROM " + table + " ORDER BY airport")
      val rows = nullColumns.take(2)
      assertEquals("[null,OTP]", rows(0).toString())
      assertEquals("[business,SFO]", rows(1).toString())
    }

    @Test
    def testJsonLoadAndSavedToEs() {
      val input = sqc.jsonFile(this.getClass.getResource("/simple.json").toURI().toString())
      println(input.schema.simpleString)

      val target = wrapIndex("spark-test/json-file")
      input.saveToEs(target, cfg)

      val basic = sqc.jsonFile(this.getClass.getResource("/basic.json").toURI().toString())
      println(basic.schema.simpleString)
      basic.saveToEs(target, cfg)

    }

    @Test
    def testJsonLoadAndSavedToEsSchema() {
      assumeFalse(readMetadata)
      val input = sqc.jsonFile(this.getClass.getResource("/multi-level-doc.json").toURI().toString())
      println("JSON schema")
      println(input.schema.treeString)
      println(input.schema)
      val sample = input.take(1)(0).toString()

      val target = wrapIndex("spark-test/json-file-schema")
      input.saveToEs(target, cfg)

      val dsCfg = collection.mutable.Map(cfg.toSeq: _*) += ("path" -> target)
      val dfLoad = sqc.load("org.elasticsearch.spark.sql", dsCfg.toMap)
      println("Reading information from Elastic")
      println(dfLoad.schema.treeString)
      println(input.schema)
      val dfload = dfLoad.take(1)(0).toString()
      
      assertEquals(input.schema.treeString, dfLoad.schema.treeString)
      assertEquals(sample, dfload)
    }

    //@Test
    // insert not supported
    def testEsDataFrame51WriteAsDataSource() {
      val target = "sparksql-test/scala-basic-write"

      var options = "resource '" + target + "'"
      if (readMetadata) {
        options = options + " ,read_metadata true"
      }

      val dataFrame = sqc.sql("CREATE TEMPORARY TABLE sqlbasicwrite " +
              "USING org.elasticsearch.spark.sql " +
              "OPTIONS (" + options + ")");


      val insertRDD = sqc.sql("INSERT INTO sqlbasicwrite SELECT 123456789, 'test-sql', 'http://test-sql.com', '', 12345")

      insertRDD.printSchema()
      assertTrue(insertRDD.count == 1)
      insertRDD.take(7).foreach(println)
    }

  def wrapIndex(index: String) = {
    prefix + index
  }
}