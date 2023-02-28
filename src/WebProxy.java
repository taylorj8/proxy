import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class WebProxy {

    ServerSocket socket;
    CountDownLatch latch;
    Listener listener;
    String hostName;
    int remotePort;
    static Pattern pattern;
    URLConnection connection;

    WebProxy(int localPort, int remotePort) {

        this.remotePort = remotePort;
        hostName = "Web Proxy";
        latch = new CountDownLatch(1);
        connection = null;
        startListener();

        pattern = Pattern.compile("(?<=Host: ).*(?=\\R)");

        try {
            socket = new ServerSocket(localPort);
//            System.out.println(socket.getLocalSocketAddress());
            listener.go();
        }
        catch(java.lang.Exception e) {e.printStackTrace();}
    }

    private void startListener()
    {
        listener = new Listener();
        listener.setDaemon(true);
        listener.start();
    }

    static class RequestHandler extends Thread {

        private final Socket client;

        public RequestHandler(Socket clientSocket)
        {
            this.client = clientSocket;
        }

        @Override
        public void run()
        {
            System.out.println("Request received");
            try
            {
                InputStream fromClient = client.getInputStream();
                byte[] request = new byte[1024];
                int length = fromClient.read(request);

                if(length > 0)
                {
                    String strRequest = new String(request, 0, length);
                    System.out.println(strRequest);

                    Matcher m = pattern.matcher(strRequest);
                    String hostName = "";
                    int port = 443;
                    if(m.find())
                    {
                        String[] hostPort = m.group().split(":");
                        hostName = hostPort[0];

                        if(hostPort.length > 1)
                        {
                            port = Integer.parseInt(hostPort[1]);
                        }
                    }

                    Socket server = new Socket(hostName, port);
                    OutputStream toServer = server.getOutputStream();
                    toServer.write(request);

                    InputStream fromServer = server.getInputStream();
                    OutputStream toClient = client.getOutputStream();

                    byte[] response = new byte[4096];
                    int chunkLength;
                    while((chunkLength = fromServer.read(response)) != -1)
                    {
                        toClient.write(response, 0, chunkLength);
                    }

                    toServer.close();
                    fromServer.close();
                    toClient.close();
                    server.close();
                }
                fromClient.close();

            }
            catch(IOException e) {e.printStackTrace();}
            finally
            {
                try
                {
                    client.close();
                }
                catch(IOException e) {e.printStackTrace();}
            }
        }
    }


    /**
     * Listener thread
     * Listens for incoming packets on a datagram socket and informs registered receivers about incoming packets.
     */
    class Listener extends Thread {

        // Tell the listener that the socket has been initialized
        public void go() {
            latch.countDown();
        }

        // Listen for incoming packets and inform receivers
        @Override
        public void run() {

            try {
                latch.await();
                // Endless loop: attempt to receive packet, notify receivers, etc
                while(true)
                {
                    Socket client = null;
                    try
                    {
                        client = socket.accept();
                        new RequestHandler(client).start();
                    }
                    catch(Exception ignored) {}
                    finally
                    {
//                        try
//                        {
//                            if(client != null)
//                                client.close();
//                        }
//                        catch(Exception ignored) {}
                    }
                }
            }
            catch (Exception e) {e.printStackTrace();}
        }
    }

    // function for passing request from client to server or reply from server to client
    private void pass(byte[] re, InputStream from, OutputStream to)
    {
        int bytesRead;
        try
        {
            while((bytesRead = from.read(re)) != -1)
            {
                to.write(re, 0, bytesRead);
                to.flush();
            }
            to.close();

        } catch(IOException ignored) {}
    }


    private synchronized void start() throws InterruptedException
    {
        System.out.println("Proxy Server running");
        this.wait();
    }

    // Main function - creates Proxy server
    public static void main(String[] args) {

        try {
            (new WebProxy(4000, 4001)).start();
            System.out.println("Program completed");
        } catch(java.lang.Exception e) {e.printStackTrace();}
    }
}

