import java.io.*;
import java.net.Socket;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.net.URL;
import java.net.URLConnection;

class Proxy {

    ServerSocket socket;
    CountDownLatch latch;
    Listener listener;
    String hostName;
    int remotePort;
    Pattern[] patterns;

    Proxy(int localPort, int remotePort) {

        this.remotePort = remotePort;
        hostName = "Web Proxy";
        latch = new CountDownLatch(1);
        startListener();

        patterns = new Pattern[2];
        patterns[0] = Pattern.compile("(?<=Host: ).*(?=\\R)");
        patterns[1] = Pattern.compile("(?<=CONNECT ).*(?=/)");

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

                        String header = new BufferedReader(new InputStreamReader(fromClient,
                                StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));

                        System.out.println(header);

                        // get all of line after "Host:"
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

                        m = patterns[1].matcher(header);
                        matchFound = m.find();

                        if(matchFound)
                        {
                            String line = m.group();
                            String[] sections = line.split(" ");

                            URL url = new URL(sections[sections.length-1] + "://" + host);
                            URLConnection connection = url.openConnection();

                            connection.setDoInput(true);
                            connection.setDoOutput(false);

                            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                            connection.setRequestProperty("Content-Language", "en-US");
                            connection.setUseCaches(false);
                            connection.setDoOutput(true);

                            System.out.println(connection.getContentLength());
                            BufferedReader proxyToServerBR = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                        }


                        final InputStream fromServer = server.getInputStream();
                        final OutputStream toServer = server.getOutputStream();

                        String ret = new BufferedReader(new InputStreamReader(fromServer,
                                StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));

                        System.out.println(ret);

                        Thread t = new Thread(() -> {

                            // request is passed from the client to the server
                            pass(request, fromClient, toServer);
                        });

                        // start client-to-server request thread
                        t.start();

                        // server's response is passed back to client
                        pass(reply, fromServer, toClient);


                    } catch(IOException e) {
                        System.err.println(e);
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
            (new Proxy(4000, 4001)).start();
            System.out.println("Program completed");
        } catch(java.lang.Exception e) {e.printStackTrace();}
    }
}

