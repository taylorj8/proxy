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
    Pattern[] patterns;
    URLConnection connection;

    WebProxy(int localPort, int remotePort) {

        this.remotePort = remotePort;
        hostName = "Web Proxy";
        latch = new CountDownLatch(1);
        connection = null;
        startListener();

        patterns = new Pattern[2];
        patterns[0] = Pattern.compile("(?<=Host: ).*(?=\\R)");
        patterns[1] = Pattern.compile(".*(?=\\R)");

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
        public void run() {

            try {
                final byte[] request = new byte[1024];
                byte[] reply = new byte[4096];

                latch.await();
                // Endless loop: attempt to receive packet, notify receivers, etc
                while(true)
                {
                    Socket client = null, server = null;
                    try
                    {
                        client = socket.accept();
                        final InputStream fromClient = client.getInputStream();
                        final OutputStream toClient = client.getOutputStream();

                        pass(request, fromClient, toClient);

                        String header = new BufferedReader(new InputStreamReader(fromClient,
                                StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));

                        System.out.println(header);

                        // get all the line after "Host:"
                        Matcher m = patterns[0].matcher(header);
                        boolean matchFound = m.find();

                        String host = "";
                        int port = 0;
                        if(matchFound)
                        {
                            String[] host_port = m.group().split(":");
                            host = host_port[0];
                            if(host_port.length >= 2)
                                port = Integer.parseInt(host_port[1]);
                        }

                        try
                        {
                            server = new Socket(host, port);
                        } catch(IOException e)
                        {
                            PrintWriter out = new PrintWriter(toClient);
                            out.print("Proxy server cannot connect to " + hostName + ":" + remotePort + ":\n" + e + "\n");
                            out.flush();
                            client.close();
                            continue;
                        }

                        // todo

                        final InputStream fromServer = server.getInputStream();
                        final OutputStream toServer = server.getOutputStream();

//                        PrintWriter pr = new PrintWriter(toServer);
//
//                        m = patterns[1].matcher(header);
//                        matchFound = m.find();
//                        if(matchFound)
//                        {
//                            String[] firstLine = m.group().split(" ");
//                            switch(firstLine[0])
//                            {
//                                case "CONNECT":
//                                    URL url = new URL(firstLine[firstLine.length-1].split("/")[0] + "://" + firstLine[1]);
//
//                                    pr.println(request);
//
//
//                                    break;
//                                case "GET":
//                                    break;
//                                default:
//                            }
//                        }


                        pass(request, fromClient, toServer);
//                        Thread t = new Thread(() -> {
//                            // request is passed from the client to the server
//
//                        });
//
//                        // start client-to-server request thread
//                        t.start();

                        // server's response is passed back to client
                        pass(reply, fromServer, toClient);

//                        String ret = new BufferedReader(new InputStreamReader(fromServer,
//                                StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
//
//                        System.out.println(ret);


                    } catch(Exception e) {
                        e.printStackTrace();
                    } finally {
                        try {
                            if(client != null)
                            {
                                client.close();
                            }
                            if(server != null)
                            {
                                server.close();
                            }

                        } catch(Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
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

