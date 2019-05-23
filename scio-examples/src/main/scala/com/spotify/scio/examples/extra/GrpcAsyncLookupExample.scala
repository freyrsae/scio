package com.spotify.scio.examples.extra

import com.google.common.cache.{Cache, CacheBuilder}
import com.spotify.scio.ContextAndArgs
import com.spotify.scio.proto.simple_grpc.CustomerOrderRequest
import com.spotify.scio.proto.simple_grpc.CustomerOrderServiceGrpc.CustomerOrderServiceStub
import com.spotify.scio.transforms.{GuavaCacheSupplier, ScalaAsyncLookupDoFn}
import io.grpc.ManagedChannelBuilder
import org.apache.beam.sdk.transforms.ParDo
import org.apache.beam.sdk.values.KV

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object GrpcAsyncLookupExample {
  def main(cmdlineArgs: Array[String]): Unit = {
    val (sc, args) = ContextAndArgs(cmdlineArgs)

    sc.parallelize(1 to 5)
      .map("userId" + _)
      .applyTransform(ParDo.of(new GrpcAsyncLookupDoFn(args("host"), args("port").toInt)))
      .map(processResult)
      .saveAsTextFile(args("output"))
    sc.close()
    ()
  }

  def processResult(kv: KV[String, Try[String]]): String =
    kv.getValue match {
      case Success(value) => s"${kv.getKey}\t$value"
      case _              => s"${kv.getKey}\tFailed"
    }
}

class GrpcAsyncLookupDoFn(host: String, port: Int)
    extends ScalaAsyncLookupDoFn[String, String, CustomerOrderServiceStub](
      10,
      new LookupCacheSupplier
    ) {

  override protected def client(): CustomerOrderServiceStub = {
    // Should only use plain text for testing
    val channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build
    new CustomerOrderServiceStub(channel)
  }

  override protected def asyncLookup(
    stub: CustomerOrderServiceStub,
    input: String
  ): Future[String] = {
    val request = CustomerOrderRequest(id = input)
    val f = stub.requestOrder(request)
    f.transform {
      case Success(res) => Success(res.orderId)
      case Failure(ex)  => Failure(ex)
    }
  }
}

class LookupCacheSupplier extends GuavaCacheSupplier[String, String, String] {
  override def createCache: Cache[String, String] =
    CacheBuilder
      .newBuilder()
      .maximumSize(100)
      .build[String, String]

  override def getKey(input: String): String = input
}
