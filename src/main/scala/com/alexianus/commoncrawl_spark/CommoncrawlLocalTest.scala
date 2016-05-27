package com.alexianus.commoncrawl_spark

import java.net.URL

import com.alexianus.wappalyzer.AppDetector
import com.martinkl.warc._
import com.martinkl.warc.mapreduce._
import org.apache.hadoop.io.LongWritable
import org.apache.spark.{SparkConf, SparkContext}

import scala.util.Try

object CommoncrawlLocalTest {
  def main(args: Array[String]) {
    val conf = new SparkConf()
      .setAppName("CommoncrawlLocalTest")
    val sc = new SparkContext

    val response_pages = sc.accumulator(0L, "response_pages")
    val unique_domains = sc.accumulator(0L, "unique_domains")
    val analyzed_pages = sc.accumulator(0L, "analyzed_pages")

    val pathPattern = java.net.URLDecoder.decode(args(0), "UTF-8")

    val warc = sc.newAPIHadoopFile(
      pathPattern,
      classOf[WARCInputFormat],
      classOf[LongWritable],
      classOf[WARCWritable]
    )

    val sample = warc
      .flatMap { case (_, record: WARCWritable) =>
        val r = record.getRecord
        if (r.getHeader.getRecordType == "response") {
          Try {
            // Ignore subdomains
            val domain = new URL(r.getHeader.getTargetURI).getHost.split("\\.").reverse.take(2).reverse.mkString(".")
            (domain, r.getContent)
          }.toOption
        } else {
          None
        }
      }
      .take(100)

    sc.parallelize(sample)
      // Discard all but one body per domain
      .combineByKey(
        identity,
        (content: Array[Byte], _: Array[Byte]) => content,
        (content: Array[Byte], _: Array[Byte]) => content
      )
      .flatMap { case (domain: String, body: Array[Byte]) =>
        Try {
          val bodyString = new String(body, "UTF-8")
          analyzed_pages += 1L
          (domain, AppDetector.detect(bodyString))
        }.toOption
      }
      .combineByKey(
        identity,
        (s1: Set[String], s2: Set[String]) => s1.union(s2),
        (s1: Set[String], s2: Set[String]) => s1.union(s2)
      )
      .saveAsTextFile(args(1))
  }
}
