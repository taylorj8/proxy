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

                        BufferedReader reader = new BufferedReader(new InputStreamReader(fromClient));
                        String line = reader.readLine();
                        System.out.println(line);

                        String[] segments = line.split(" ");
                        String host = reader.readLine();

                        try
                        {
                            server = new Socket(host.split(" ")[1], 443);
                        } catch(IOException e)
                        {
                            PrintWriter out = new PrintWriter(toClient);
                            out.print("Proxy server cannot connect to " + hostName + ":" + remotePort + ":\n" + e + "\n");
                            out.flush();
                            client.close();
                            continue;
                        }

                        final InputStream fromServer = server.getInputStream();
                        final OutputStream toServer = server.getOutputStream();

                        PrintWriter wtr = new PrintWriter(toServer);
                        String[] temp = segments[1].split("/");

                        if(segments[0].equals("CONNECT") || segments[0].equals("POST"))
                        {
                            wtr.println(line);
                            wtr.println(host);
                            for(int i = 0; i < 3; i++)
                            {
                                wtr.println(reader.readLine());
                            }
                            wtr.flush();
                        }
                        if(segments[0].equals("GET"))
                        {
                            String write = segments[0]+ " " + temp[temp.length-1] + " " + segments[segments.length-1];
                            wtr.println(write);
                            wtr.println(host);
                            wtr.println("");
                            wtr.flush();
                        }

                        BufferedReader bufRead = new BufferedReader(new InputStreamReader(fromServer));
                        String outStr;

                        while((outStr = bufRead.readLine()) != null){
                            System.out.println(outStr);
                        }

                        bufRead.close();
                        wtr.close();

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

