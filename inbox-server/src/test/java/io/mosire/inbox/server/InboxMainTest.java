package io.mosire.inbox.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboxMainTest {
  @TempDir Path temp;

  @Test
  void initIssueDoctorAndRevokeWorkThroughCli() throws Exception {
    PrintStream originalOut = System.out;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    try {
      assertThat(InboxMain.run(new String[] {"init", "--data-dir", temp.toString()})).isZero();
      assertThat(
              InboxMain.run(
                  new String[] {
                    "token",
                    "issue",
                    "--data-dir",
                    temp.toString(),
                    "--label",
                    "cli",
                    "--scope",
                    ApiScopes.STATUS_READ,
                    "--inbox",
                    "main"
                  }))
          .isZero();
      String issued = out.toString(StandardCharsets.UTF_8);
      assertThat(issued).contains("token=inbox_");
      String credentialId =
          issued.lines()
              .filter(line -> line.startsWith("credential_id="))
              .findFirst()
              .orElseThrow()
              .substring("credential_id=".length());
      assertThat(InboxMain.run(new String[] {"doctor", "--data-dir", temp.toString()})).isZero();
      assertThat(
              InboxMain.run(
                  new String[] {
                    "token", "revoke", "--data-dir", temp.toString(), "--id", credentialId
                  }))
          .isZero();
    } finally {
      System.setOut(originalOut);
    }
  }

  @Test
  void nonLoopbackServerConfigIsRejected() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new ServerConfig(
                    temp,
                    "0.0.0.0",
                    18765,
                    "127.0.0.1",
                    18766,
                    "/mcp",
                    java.time.Duration.ofSeconds(15),
                    "main"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("回环");
  }
}
