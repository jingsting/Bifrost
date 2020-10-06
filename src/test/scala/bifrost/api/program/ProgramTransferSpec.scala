package bifrost.api.program

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.ByteString
import bifrost.http.api.routes.ProgramApiRoute
import bifrost.modifier.box.Box
import io.circe.parser.parse
import scorex.util.encode.Base58
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ProgramTransferSpec extends AnyWordSpec
  with Matchers
  with ScalatestRouteTest
  with ProgramMockState {

  val route: Route = ProgramApiRoute(settings, nodeViewHolderRef).route

  "ProgramTransfer" should {

    val boxState: Set[Box] = Set(stateBox, codeBox, executionBox)

    manuallyApplyBoxes(boxState, 1)

    "Transfer a program and create a new ExecutionBox with the updated owner" in {

      val requestBody = ByteString(
        s"""{
           |  "jsonrpc": "2.0",
           |  "id": "1",
           |  "method": "transferProgram",
           |  "params": [{
           |    "from": "$publicKey",
           |    "to": "$publicKey",
           |    "programId": "${Base58.encode(executionBox.id)}",
           |    "fee": 0,
           |    "data": ""
           |  }]
           |}
           |""".stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res = parse(responseAs[String]).right.get

        (res \\ "result").head.asObject.isDefined shouldEqual true
        (res \\ "error").isEmpty shouldEqual true
      }
    }
  }
}
