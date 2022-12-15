package org.example;

import io.avaje.http.client.HttpClientContext;
import io.helidon.common.http.Http;
import io.helidon.nima.webserver.WebServer;
import io.helidon.nima.webserver.http.HttpRouting;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
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
          res.send("good".getBytes(StandardCharsets.UTF_8));
        })
        .get("bad", (req, res) -> {
          res.status(Http.Status.OK_200);
          OutputStream outputStream = res.outputStream();
          // error occurs before writing content to outputStream
          throw new RuntimeException("Error After res.outputStream() called");
        })
        .error(Exception.class, (req, res, e) -> {
          res.status(Http.Status.I_AM_A_TEAPOT_418);
          res.send("MyErrorMessage".getBytes(StandardCharsets.UTF_8));
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
    } finally {
      myServer.stop();
    }
  }
}
