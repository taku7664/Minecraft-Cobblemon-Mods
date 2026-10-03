package jbro.cobblemon.mcc.internal.wiki

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOption
import io.netty.channel.epoll.EpollSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.util.ReferenceCountUtil
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.WeakHashMap
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.minecraft.network.Connection

/**
 * Serves the wiki on the game's own port, so players reach it at the address they play on with no other port to
 * open: a connection whose first bytes are an HTTP method is relayed to the wiki's own listener on this machine, and
 * every other connection goes on to Minecraft untouched. A Minecraft handshake starts with its length and packet ID
 * 0, so it never reads as "GET " or "HEAD".
 */
object WikiPortSharing {
    private val METHODS = listOf("GET ", "HEAD").map { it.toByteArray(StandardCharsets.US_ASCII) }

    /** The address and port each connection's client typed to reach the server, from its handshake. */
    private val addresses: MutableMap<Connection, String> = Collections.synchronizedMap(WeakHashMap())

    /** Puts the sniffer in front of a newly accepted game connection. */
    @JvmStatic
    fun install(channel: Channel) {
        channel.pipeline().addFirst("mcc_wiki_sniffer", Sniffer())
    }

    /** The address the latest player from elsewhere typed, for links handed out outside the game. */
    @Volatile
    var lastPublicBase: String? = null
        private set

    @JvmStatic
    fun rememberAddress(connection: Connection, hostName: String, port: Int) {
        val address = address(hostName, port) ?: return
        addresses[connection] = address
        if (!isLoopback(hostName)) lastPublicBase = "http://$address"
    }

    private fun isLoopback(hostName: String): Boolean {
        val host = hostName.substringBefore('\u0000').trim().lowercase()
        return host == "localhost" || host.startsWith("127.") || host == "::1" || host == "0.0.0.0"
    }

    /** `http://<address the player typed>`, or null when their handshake left none. */
    fun baseFor(connection: Connection): String? = addresses[connection]?.let { "http://$it" }

    /** `host:port` for a URL; modded clients may append "\u0000..." markers to the host. */
    internal fun address(hostName: String, port: Int): String? {
        val host = hostName.substringBefore('\u0000').trim().trimEnd('.')
        if (host.isEmpty() || port !in 1..65535) return null
        return "${if (':' in host) "[$host]" else host}:$port"
    }

    internal fun isHttp(buffer: ByteBuf): Boolean {
        if (buffer.readableBytes() < 4) return false
        val start = buffer.readerIndex()
        return METHODS.any { method -> method.indices.all { buffer.getByte(start + it) == method[it] } }
    }

    private class Sniffer : ChannelInboundHandlerAdapter() {
        override fun channelRead(context: ChannelHandlerContext, message: Any) {
            context.pipeline().remove(this)
            if (message !is ByteBuf || !WikiServer.running || !isHttp(message)) {
                context.fireChannelRead(message)
                return
            }
            // Minecraft's handlers stay in the pipeline but never see a byte; its idle timeout would cut a slow page.
            context.pipeline().get("timeout")?.let { context.pipeline().remove(it) }
            relay(context.channel(), message)
        }
    }

    private fun relay(client: Channel, first: ByteBuf) {
        client.config().isAutoRead = false
        val connect = Bootstrap().group(client.eventLoop())
            .channel(if (client is EpollSocketChannel) EpollSocketChannel::class.java else NioSocketChannel::class.java)
            .option(ChannelOption.AUTO_READ, true)
            // Written from the relay's own place at the front of the pipeline, past Minecraft's packet encoders.
            .handler(Forward(client) { client.pipeline().context(RELAY) })
            .connect("127.0.0.1", WikiServer.config.port)
        val wiki = connect.channel()
        client.pipeline().addFirst(RELAY, Forward(wiki))
        connect.addListener(ChannelFutureListener { future ->
            if (future.isSuccess) {
                wiki.writeAndFlush(first)
                client.config().isAutoRead = true
            } else {
                first.release()
                MoreCobblemonContents.LOGGER.warn("Wiki relay could not reach the wiki on port {}", WikiServer.config.port, future.cause())
                client.close()
            }
        })
    }

    private const val RELAY = "mcc_wiki_relay"

    /**
     * Copies everything one side reads to the other, and closes the other once this side closes. Writes go through
     * [via] when given, so they leave from that handler's place in the target's pipeline.
     */
    private class Forward(
        private val target: Channel,
        private val via: () -> ChannelHandlerContext? = { null },
    ) : ChannelInboundHandlerAdapter() {
        private fun write(message: Any) = via()?.writeAndFlush(message) ?: target.writeAndFlush(message)

        override fun channelRead(context: ChannelHandlerContext, message: Any) {
            if (target.isActive) write(message) else ReferenceCountUtil.release(message)
        }

        override fun channelInactive(context: ChannelHandlerContext) {
            if (target.isActive) write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
        }

        @Deprecated("Deprecated in Java")
        override fun exceptionCaught(context: ChannelHandlerContext, cause: Throwable) {
            context.close()
        }
    }
}
