package Messaging;

import Consensus.Leader;
import Constants.ChatServerConstants;
import Constants.ServerProperties;
import Server.Server;
import Server.ServerState;
import Exception.ServerException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import Constants.ServerProperties;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class Messaging {

    private static final Logger logger = LogManager.getLogger(Messaging.class);

    /**
     * JSON parse a given string.
     *
     * @param jsonString - String containing json object
     * @return - JSONObject
     * @throws ParseException
     */
    public static JSONObject jsonParseRequest(String jsonString) throws ParseException {

        JSONParser jsonParser = new JSONParser();
        return (JSONObject) jsonParser.parse(jsonString);
    }

    /**
     * Send a given json response via a given socket.
     *
     * @param obj    - Response as a json object
     * @param socket - Socket of the receiver.
     * @throws IOException
     */
    public static void respond(JSONObject obj, Socket socket) throws IOException {
        DataOutputStream dataOutputStream = new DataOutputStream(socket.getOutputStream());
        dataOutputStream.write((obj.toJSONString() + "\n").getBytes(StandardCharsets.UTF_8));
        dataOutputStream.flush();
    }


    public static void broadcastToPreviousRoom(){
        //TODO: Implement the broadcasting to previous server
    }

    public static void broadcastServers(JSONObject obj) throws IOException {
        Collection<Server> servers = ServerState.getServerState().getServers();
        for (Server server : servers) {
            Socket socket = new Socket(server.getAddress(), server.getPort());
            DataOutputStream dataOutputStream = new DataOutputStream(socket.getOutputStream());
            dataOutputStream.write((obj.toJSONString() + "\n").getBytes(StandardCharsets.UTF_8));
            dataOutputStream.flush();
            socket.close();
        }
    }

    /**
     * Only executed by the leader.
     * Asks something from each server in the servers Collection
     *
     * @param request - request Json.
     * @param servers - List of servers.
     * @return - Responses Map.
     */
    public static ConcurrentHashMap<String, JSONObject> askServers(JSONObject request, Collection<Server> servers) {
        ConcurrentHashMap<String, JSONObject> serverResponses = new ConcurrentHashMap<>();
        ExecutorService executorService = Executors.newFixedThreadPool(ServerProperties.THREAD_COUNT);
        for (Server server : servers) {
            executorService.submit(() -> {
                try {
                    Socket socket = new Socket(server.getAddress(), server.getPort());
                    String line = sendRequest(request, socket);
                    serverResponses.put(server.getId(), jsonParseRequest(line));
                    socket.close();
                } catch (Exception e) {
                    logger.info("Connection failed for server: " + server.getAddress() + ":" + server.getPort());
                    logger.debug(e.getMessage());
                }
            });
        }
        executorService.shutdown();
        try {
            //wait till completion or 5s or interruption of this thread
            if (!executorService.awaitTermination(ServerProperties.CONN_TIMEOUT, TimeUnit.MILLISECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        return serverResponses;
    }

    public static JSONObject contactLeader(JSONObject request, Leader leader) throws ServerException, IOException, ParseException {

        Socket socket = new Socket(leader.getAddress(), leader.getPort());
        socket.setSoTimeout((int) ServerProperties.CONN_TIMEOUT);
        try {
            String line = sendRequest(request, socket);
            socket.close();
            return jsonParseRequest(line);
        } catch (SocketTimeoutException e){
            // Throw an error if connection to leader fails.
            logger.info("Connection to leader timed out.");
            throw new ServerException(
                    ChatServerConstants.ServerExceptionConstants.LEADER_FAILED_MSG,
                    ChatServerConstants.ServerExceptionConstants.LEADER_FAILED_CODE);
        }
    }

    private static String sendRequest(JSONObject request, Socket socket) throws IOException {
        DataOutputStream dataOutputStream = new DataOutputStream(socket.getOutputStream());
        dataOutputStream.write((request.toJSONString() + "\n").getBytes(StandardCharsets.UTF_8));
        dataOutputStream.flush();

        InputStream inputFromClient = socket.getInputStream();
        Scanner serverInputScanner = new Scanner(inputFromClient, String.valueOf(StandardCharsets.UTF_8));
        String line = serverInputScanner.nextLine();
        logger.debug("Received: " + line);
        return line;
    }
}
