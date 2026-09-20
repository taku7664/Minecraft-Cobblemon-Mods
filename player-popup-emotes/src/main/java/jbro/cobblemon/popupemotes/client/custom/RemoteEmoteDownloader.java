package jbro.cobblemon.popupemotes.client.custom;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import jbro.cobblemon.popupemotes.emote.RemoteEmoteUrlPolicy;

final class RemoteEmoteDownloader {
    static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private RemoteEmoteDownloader() {
    }

    static byte[] download(String value) throws IOException, InterruptedException {
        URI uri;
        try {
            uri = RemoteEmoteUrlPolicy.requireUri(value);
        } catch (Exception exception) {
            throw new IOException("Unsafe image URL", exception);
        }
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            requirePublicHost(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "image/png,image/jpeg,image/gif")
                .header("User-Agent", "Player-Popup-Emotes/0.2")
                .GET()
                .build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                try (InputStream ignored = response.body()) {
                    String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new IOException("Redirect has no location"));
                    uri = uri.resolve(location);
                    if (!RemoteEmoteUrlPolicy.acceptsSyntax(uri.toString())) {
                        throw new IOException("Redirected to an unsafe URL");
                    }
                    continue;
                }
            }
            if (status < 200 || status >= 300) {
                response.body().close();
                throw new IOException("Image server returned HTTP " + status);
            }
            long declaredLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (declaredLength > MAX_FILE_BYTES) {
                response.body().close();
                throw new IOException("Image exceeds the 5 MB limit");
            }
            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
            if (!contentType.isEmpty() && !contentType.startsWith("image/")) {
                response.body().close();
                throw new IOException("URL did not return an image");
            }
            try (InputStream input = response.body()) {
                return readLimited(input);
            }
        }
        throw new IOException("Too many image redirects");
    }

    private static void requirePublicHost(URI uri) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        if (addresses.length == 0) {
            throw new IOException("Image host did not resolve");
        }
        for (InetAddress address : addresses) {
            if (!RemoteEmoteUrlPolicy.isPublic(address)) {
                throw new IOException("Image host resolves to a private address");
            }
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_FILE_BYTES) {
                throw new IOException("Image exceeds the 5 MB limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
