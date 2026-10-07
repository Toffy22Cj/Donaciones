package com.traceability.app.auth;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * HTTP/1.1 por socket en bruto contra el servidor real: la ruta llega a Tomcat exactamente como se escribe, sin que
 * ningún cliente HTTP la normalice. Solo para tests.
 */
final class RawHttp {

    record Response(int status, String body) {}

    private RawHttp() {}

    static Response send(int port, String method, String rawPath, String bearer, String jsonBody) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            byte[] body = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
            StringBuilder req = new StringBuilder()
                    .append(method).append(' ').append(rawPath).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1:").append(port).append("\r\n")
                    .append("Connection: close\r\n");
            if (bearer != null) req.append("Authorization: Bearer ").append(bearer).append("\r\n");
            if (jsonBody != null) req.append("Content-Type: application/json\r\n");
            req.append("Content-Length: ").append(body.length).append("\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            int status = Integer.parseInt(in.readLine().split(" ")[1]);
            boolean chunked = false;
            for (String line; (line = in.readLine()) != null && !line.isEmpty(); ) {
                chunked |= line.toLowerCase().startsWith("transfer-encoding:") && line.toLowerCase().contains("chunked");
            }
            StringBuilder rest = new StringBuilder();
            if (chunked) {
                for (String size; (size = in.readLine()) != null; ) {
                    int n = Integer.parseInt(size.trim().split(";")[0], 16);
                    if (n == 0) break;
                    char[] chunk = new char[n];
                    int read = 0;
                    while (read < n) read += in.read(chunk, read, n - read);
                    rest.append(chunk);
                    in.readLine();
                }
            } else {
                for (String line; (line = in.readLine()) != null; ) rest.append(line);
            }
            return new Response(status, rest.toString());
        }
    }
}
