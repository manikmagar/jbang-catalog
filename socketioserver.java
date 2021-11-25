///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.5.0
//DEPS io.socket:socket.io-server:3.0.1,io.socket:engine.io-server:5.0.0,io.socket:engine.io-server-jetty:4.0.3
//DEPS org.eclipse.jetty:jetty-server:9.4.43.v20210629,org.eclipse.jetty:jetty-servlet:9.4.43.v20210629,org.eclipse.jetty.websocket:websocket-server:9.4.43.v20210629

import io.socket.engineio.client.Socket;
import io.socket.engineio.server.EngineIoServer;
import io.socket.engineio.server.EngineIoServerOptions;
import io.socket.engineio.server.JettyWebSocketHandler;
import io.socket.socketio.server.SocketIoNamespace;
import io.socket.socketio.server.SocketIoServer;
import io.socket.socketio.server.SocketIoSocket;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.json.JSONException;
import org.json.JSONObject;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Command(name = "socketioserver", mixinStandardHelpOptions = true, version = "socketioserver 0.1",
        description = "Socket.io Server. Point your client to http://localhost:${port}. " +
                "Send/Receive from Server - event type 'MSG'. " +
                "Listen to Server broadcast - srv_response")
class socketioserver implements Callable<Integer> {

    @Parameters(index = "0", description = "Server Listener port", defaultValue = "8080")
    private int port;

    public static void main(String... args) {
        int exitCode = new CommandLine(new socketioserver()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        ChatServer chatServer = new ChatServer();
        ChatServletHandler chatServletHandler = new ChatServletHandler(chatServer.mEngineIoServer, port);
        chatServletHandler.start();

        return 0;
    }
}

class ChatServletHandler {
    final EngineIoServer mEngineIoServer;
    private final int port;

    ChatServletHandler(EngineIoServer engineIoServer, int port) {
        this.mEngineIoServer = engineIoServer;
        this.port = port;
    }

    public void start() throws Exception {
        System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog");

        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
                mEngineIoServer.handleRequest(new HttpServletRequestWrapper(req), resp);
            }
        };


        ServletContextHandler handler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        handler.setContextPath("/");
        handler.addServlet(new ServletHolder(servlet), "/socket.io/*");

        try {
            WebSocketUpgradeFilter webSocketUpgradeFilter = WebSocketUpgradeFilter.configure(handler);
            webSocketUpgradeFilter.addMapping(
                    new ServletPathSpec("/socket.io/*"),
                    (servletUpgradeRequest, servletUpgradeResponse) -> new JettyWebSocketHandler(mEngineIoServer));
        } catch (ServletException ex) {
            System.err.println("WebSocket is not available");
        }


        var server = new Server(port);
        HandlerList handlerList = new HandlerList();
        handlerList.setHandlers(new Handler[]{handler});
        server.setHandler(handlerList);

        server.start();
        server.join();
    }
}
class ChatServer {
    public static final String SRV_RESPONSE = "srv_response";
    final EngineIoServer mEngineIoServer = new EngineIoServer(EngineIoServerOptions
            .newFromDefault()
            .setPingTimeout(3000));
    final SocketIoServer mSocketIoServer = new SocketIoServer(mEngineIoServer);

    final ScheduledExecutorService ses = new ScheduledThreadPoolExecutor(4);


    ChatServer() throws Exception {
        configure();
    }

    public EngineIoServer getEngineIoServer() {
        return mEngineIoServer;
    }

    private void configure() throws Exception {
        SocketIoNamespace namespace = mSocketIoServer.namespace("/");
        namespace.on("connection", args -> {
            SocketIoSocket socket = (SocketIoSocket) args[0];
            socket.send("connected", "Connected");
            handleSocketJoin(socket);
            handleAllMessages(socket);
        });

        ses.scheduleAtFixedRate(() ->
                 namespace.broadcast(null, SRV_RESPONSE, "Server sent data"),
                 10, 5, TimeUnit.SECONDS);
    }

    private void handleAllMessages(SocketIoSocket socket) {
         socket.registerAllEventListener((eventType, objects) -> {
             socket.broadcast(null,eventType,
                     String.format("{socket: %s, msg: %s}", socket.getId(), objects[0]));
         });
    }


    private void handleSocketJoin(SocketIoSocket socket) {
        socket.on("join", args -> {
            if(args[0] instanceof JSONObject) {
                try{
                    String room = ((JSONObject) args[0]).getString("room");
                    socket.joinRoom(room);
                    socket.send(SRV_RESPONSE, "Entered room " + room);
                } catch (JSONException ex) {
                    socket.send(Socket.EVENT_ERROR, "Invalid Data");
                }
            }
        });
    }
}