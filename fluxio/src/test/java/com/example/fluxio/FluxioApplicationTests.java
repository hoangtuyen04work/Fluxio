package com.example.fluxio;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "fluxio.password=test-password")
class FluxioApplicationTests {
    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void signsUploadAndDownloadForAnAuthenticatedAccount() throws Exception {
        var session = login("test-password");
        assertThat(session.statusCode()).isEqualTo(200);
        var token = json.readTree(session.body()).get("accessToken").asText();
        var upload = send(request("/api/v1/mvp/files").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(upload.statusCode()).isEqualTo(200);
        var ticket = json.readTree(upload.body());
        var id = ticket.get("fileId").asText();
        var putUrl = URI.create(ticket.get("url").asText());

        var download = send(request("/api/v1/mvp/files/" + id)
                .header("Authorization", "Bearer " + token).GET());
        assertThat(download.statusCode()).isEqualTo(200);
        var getUrl = URI.create(json.readTree(download.body()).get("url").asText());
        assertThat(getUrl.getPath()).isEqualTo(putUrl.getPath()).endsWith("/" + id);
        assertThat(getUrl.getQuery()).contains("X-Amz-Signature=").isNotEqualTo(putUrl.getQuery());
    }

    @Test
    void rejectsInvalidCredentialsAndAnonymousTransfers() throws Exception {
        assertThat(login("incorrect").statusCode()).isEqualTo(401);
        assertThat(send(request("/api/v1/mvp/files")
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> login(String password) throws Exception {
        return send(request("/api/v1/auth/login").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(new LoginController.Credentials("fluxio", password)))));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
