package jbro.cobblemon.popupemotes.client.custom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import jbro.cobblemon.popupemotes.emote.RemoteEmoteUrlPolicy;
import org.junit.jupiter.api.Test;

final class RemoteEmoteUrlPolicyTest {
    @Test
    void acceptsOrdinaryHttpsUrlsAndRejectsUnsafeSchemesAndLiteralHosts() throws Exception {
        assertTrue(RemoteEmoteUrlPolicy.acceptsSyntax("https://cdn.example.com/emotes/hello.gif"));
        assertTrue(RemoteEmoteUrlPolicy.acceptsSyntax("https://cdn.example.com/image?id=1"));

        assertFalse(RemoteEmoteUrlPolicy.acceptsSyntax("http://example.com/emote.png"));
        assertFalse(RemoteEmoteUrlPolicy.acceptsSyntax("file:///C:/secret.png"));
        assertFalse(RemoteEmoteUrlPolicy.acceptsSyntax("https://localhost/emote.png"));
        assertFalse(RemoteEmoteUrlPolicy.acceptsSyntax("https://127.0.0.1/emote.png"));
        assertFalse(RemoteEmoteUrlPolicy.acceptsSyntax("https://192.168.0.5/emote.png"));
        assertFalse(RemoteEmoteUrlPolicy.acceptsSyntax("https://[::1]/emote.png"));
    }

    @Test
    void identifiesImgurPagesThatAreNotDirectImages() {
        assertTrue(RemoteEmoteUrlPolicy.isKnownWebPage("https://imgur.com/a/DMZ0kSi"));
        assertTrue(RemoteEmoteUrlPolicy.isKnownWebPage("https://imgur.com/gallery/DMZ0kSi"));
        assertTrue(RemoteEmoteUrlPolicy.isKnownWebPage("https://imgur.com/DMZ0kSi"));

        assertFalse(RemoteEmoteUrlPolicy.isKnownWebPage("https://i.imgur.com/DMZ0kSi.gif"));
        assertFalse(RemoteEmoteUrlPolicy.isKnownWebPage("https://cdn.example.com/image?id=1"));
    }

    @Test
    void rejectsEveryNonPublicResolvedAddress() throws Exception {
        assertFalse(RemoteEmoteUrlPolicy.isPublic(InetAddress.getByName("127.0.0.1")));
        assertFalse(RemoteEmoteUrlPolicy.isPublic(InetAddress.getByName("10.0.0.1")));
        assertFalse(RemoteEmoteUrlPolicy.isPublic(InetAddress.getByName("169.254.1.1")));
        assertFalse(RemoteEmoteUrlPolicy.isPublic(InetAddress.getByName("192.168.1.1")));
        assertFalse(RemoteEmoteUrlPolicy.isPublic(InetAddress.getByName("::1")));
        assertTrue(RemoteEmoteUrlPolicy.isPublic(InetAddress.getByName("1.1.1.1")));
    }
}
