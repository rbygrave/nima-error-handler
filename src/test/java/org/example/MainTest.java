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

class MainTest {

  public static void main(String[] args) {
    int port = new MyServer().port(8081).run();
    System.out.println("Running on port " + port);
    // curl -v http://localhost:8081/bad
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
        .get("bad", (req, res) -> {
          res.status(Http.Status.OK_200);
          res.header("main-handler-header", "a");
          OutputStream outputStream = res.outputStream();
          // error occurs before writing content to outputStream
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
  void bad_expect_418AndErrorHandlerBody() {
    MyServer myServer = new MyServer();
    try {
      int port = myServer.run();
      var httpClient = httpClient(port);

      HttpResponse<String> badResponse = httpClient.request().path("bad").GET().asString();
      // we hope to get our error handler response, but we don't get it due to internal error
      assertThat(badResponse.statusCode()).isEqualTo(418); // Fail: Get 500
      assertThat(badResponse.body()).isEqualTo("MyErrorMessage");
      assertThat(badResponse.headers().firstValue("main-handler-header")).isEmpty();
      assertThat(badResponse.headers().firstValue("error-handler-header")).isPresent().get().isEqualTo("b");

    } finally {
      myServer.stop();
    }
  }
}
