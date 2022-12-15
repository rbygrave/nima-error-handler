package org.example;

import io.avaje.http.client.HttpClientContext;
import io.helidon.common.http.Http;
import io.helidon.nima.webserver.WebServer;
import io.helidon.nima.webserver.http.HttpRouting;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainTest {

  public static void main(String[] args) {
    int port = new MyServer().port(8081).run();
    System.out.println("Running on port " + port);
    // curl -v http://localhost:8081/good
    // curl -v http://localhost:8081/outputStream-noWrites-thenError
    // curl -v http://localhost:8081/outputStream-1write-still-recoverable
    // curl -v http://localhost:8081/outputStream-2writes-not-recoverable
  }

  static class MyServer {
    WebServer webServer;
    int port;

    MyServer port(int port) {
      this.port = port;
      return this;
    }

    int run() {
      final var builder = HttpRouting.builder()
        .get("good", (req, res) -> {
          res.status(Http.Status.OK_200);
          res.header("main-handler-header", "a");
          res.send("good".getBytes(StandardCharsets.UTF_8));
        })
        .get("good-chunked", (req, res) -> {
          res.status(Http.Status.OK_200).header("Content-Type", "text/plain");
          try (OutputStream os = res.outputStream()) {
            os.write("SomeContent".getBytes(StandardCharsets.ISO_8859_1));
            os.write("MoreContent".getBytes(StandardCharsets.ISO_8859_1));
          }
        })
        .get("outputStream-noWrites-thenError", (req, res) -> {
          res.status(Http.Status.OK_200);
          res.header("main-handler-header", "a");
          OutputStream outputStream = res.outputStream();
          // error occurs before writing content to outputStream
          throw new RuntimeException("Error After res.outputStream() called");
        })
        .get("outputStream-1write-still-recoverable", (req, res) -> {
          res.status(Http.Status.OK_200);
          res.header("main-handler-header", "a");
          OutputStream outputStream = res.outputStream();
          // the first write doesn't send but held as firstBuffer which is still recoverable from
          outputStream.write("SomeContent".getBytes(StandardCharsets.UTF_8));
          // error occurs after writing ONCE to outputStream - but not twice + no os.flush() + no os.close()
          throw new RuntimeException("Error After res.outputStream() called");
        })
        .get("outputStream-2writes-not-recoverable", (req, res) -> {
          res.status(Http.Status.OK_200);
          res.header("Content-Type", "text/plain");
          res.header("main-handler-header", "a");
          OutputStream outputStream = res.outputStream();
          // the first write doesn't send but held as firstBuffer which is still recoverable from
          outputStream.write("SomeContent".getBytes(StandardCharsets.ISO_8859_1));
          // the second write will actually trigger the sending of response headers and some chunked body
          // content and this response SHOULD be isSent() TRUE and no longer able to be reset()
          outputStream.write("MoreContent".getBytes(StandardCharsets.ISO_8859_1));
          // error occurs after writing ONCE to outputStream (but not twice, not os.flush() and not os.close()
          throw new RuntimeException("Error After res.outputStream() called");
        })
        .error(Exception.class, (req, res, e) -> {
          res.status(Http.Status.I_AM_A_TEAPOT_418);
          res.header("error-handler-header", "b");
          OutputStream os = res.outputStream();
          try {
            os.write("MyErrorMessage".getBytes(StandardCharsets.UTF_8));
            os.close();
          } catch (IOException ex) {
            throw new UncheckedIOException(ex);
          }
        });

      webServer = WebServer.builder()
        .addRouting(builder.build())
        .port(port)
        .start();
      return webServer.port();
    }

    void stop() {
      webServer.stop();
    }
  }

  private static HttpClientContext httpClient(int port) {
    return HttpClientContext.builder()
      .baseUrl("http://localhost:" + port)
      .build();
  }

  @Test
  void good_expect_200AndGoodBody() {
    MyServer myServer = new MyServer();
    try {
      int port = myServer.run();
      var httpClient = httpClient(port);

      HttpResponse<String> goodResponse = httpClient.request().path("good").GET().asString();
      assertThat(goodResponse.statusCode()).isEqualTo(200);
      assertThat(goodResponse.headers().firstValue("main-handler-header")).isPresent().get().isEqualTo("a");

      assertThat(goodResponse.body()).isEqualTo("good");
    } finally {
      myServer.stop();
    }
  }

  @Test
  void outputStream_noWritesThenError_expect_418AndErrorHandlerBody() {
    MyServer myServer = new MyServer();
    try {
      int port = myServer.run();
      var httpClient = httpClient(port);

      HttpResponse<String> badResponse = httpClient.request().path("outputStream-noWrites-thenError").GET().asString();
      // we hope to get our error handler response, but we don't get it due to internal error
      assertThat(badResponse.statusCode()).isEqualTo(418); // Fail: Get 500
      assertThat(badResponse.body()).isEqualTo("MyErrorMessage");
      assertThat(badResponse.headers().firstValue("main-handler-header")).isEmpty();
      assertThat(badResponse.headers().firstValue("error-handler-header")).isPresent().get().isEqualTo("b");

    } finally {
      myServer.stop();
    }
  }

  @Test
  void outputStream_1writeThenError_expect_ErrorHandlerBody_asStillRecoverable() {
    MyServer myServer = new MyServer();
    try {
      int port = myServer.run();
      var httpClient = httpClient(port);

      HttpResponse<String> badResponse = httpClient.request().path("outputStream-1write-still-recoverable").GET().asString();
      // we hope to get our error handler response, but we don't get it due to internal error
      assertThat(badResponse.statusCode()).isEqualTo(418); // Fail: Get 500
      assertThat(badResponse.body()).isEqualTo("MyErrorMessage");
      assertThat(badResponse.headers().firstValue("main-handler-header")).isEmpty();
      assertThat(badResponse.headers().firstValue("error-handler-header")).isPresent().get().isEqualTo("b");

      // note: this test passes if isSent() is implemented as below [and ServerResponse.reset() exists and is called]
      //      @Override
      //      public boolean isSent() {
      //        return isSent || outputStream.totalBytesWritten() > 0;
      //      }

    } finally {
      myServer.stop();
    }
  }

  /**
   * All good and as expected here.
   */
  @Test
  void outputStream_2writesThenError_expect_nonRecoverable_ClientGetsIOException_asServerClosesConnection() {
    MyServer myServer = new MyServer();
    try {
      int port = myServer.run();
      var httpClient = httpClient(port);

      assertThatThrownBy(() -> httpClient.request().path("outputStream-2writes-not-recoverable").GET().asString())
        .isInstanceOf(io.avaje.http.client.HttpException.class)
        .hasCauseInstanceOf(IOException.class);

    } finally {
      myServer.stop();
    }
  }
}
