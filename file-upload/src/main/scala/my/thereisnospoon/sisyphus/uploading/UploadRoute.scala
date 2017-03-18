package my.thereisnospoon.sisyphus.uploading

import java.nio.file.{Path, Paths}

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream._
import akka.stream.scaladsl.GraphDSL.Implicits._
import akka.stream.scaladsl.{Broadcast, FileIO, GraphDSL, RunnableGraph, Sink, Source}
import akka.util.ByteString
import my.thereisnospoon.sisyphus.uploading.processing.HashingSink

import scala.concurrent.Future

class UploadRoute(tempFilesFolder: String) {

  val route: Route = post {
    path("upload") {

      extractRequestContext { ctx =>
        implicit val materializer = ctx.materializer

        fileUpload("file") {
          case (_, byteSource) =>

            val tempFileName = java.util.UUID.randomUUID().toString
            val tempFilePath = Paths.get(tempFilesFolder, tempFileName)

            val graph: RunnableGraph[(Future[IOResult], Future[IOResult], Future[String])] =
              uploadProcessingGraph(byteSource, tempFilePath)

            val (localIO, persistentStorageIO, hashFuture) = graph.run()

            complete("")
        }
      }
    }
  }

  private def uploadProcessingGraph(
                                     byteSource: Source[ByteString, Any],
                                     tempFilePath: Path
                                   ): RunnableGraph[(Future[IOResult], Future[IOResult], Future[String])] = {

    val localFileSink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(tempFilePath)
    val persistentStorageSink: Sink[ByteString, Future[IOResult]] = Sink.fromGraph(new S3SinkStub)
    val hashingSink: Sink[ByteString, Future[String]] = Sink.fromGraph(new HashingSink)

    RunnableGraph.fromGraph(GraphDSL.create(
      localFileSink,
      persistentStorageSink,
      hashingSink)((_, _, _)) { implicit builder => (lfSink, psSink, hashSink) =>

      val uploadingFile: Outlet[ByteString] = builder.add(byteSource).out
      val fanOut: UniformFanOutShape[ByteString, ByteString] = builder.add(Broadcast[ByteString](3))

      uploadingFile ~> fanOut ~> lfSink
                       fanOut ~> psSink
                       fanOut ~> hashSink

      ClosedShape
    })
  }
}