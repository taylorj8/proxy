import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


class WebProxy {

    ServerSocket socket;
    CountDownLatch latch;
    Listener listener;
    String hostName;
    int remotePort;
    static Pattern[] pattern;
    URLConnection connection;
    HashSet<String> blacklist;

    WebProxy(int localPort, int remotePort) {

        this.remotePort = remotePort;
        hostName = "WebProxy";
        latch = new CountDownLatch(1);
        connection = null;
        blacklist = new HashSet<>();

        listener = new Listener();
        listener.setDaemon(true);

        pattern = new Pattern[2];
        pattern[0] = Pattern.compile(".*(?=\\R)");
        pattern[1] = Pattern.compile("(?<=Host: ).*(?=\\R)");

        try {
            socket = new ServerSocket(localPort);
//            System.out.println(socket.getLocalSocketAddress());
        }
        catch(java.lang.Exception e) {e.printStackTrace();}
    }

    static class RequestHandler extends Thread {

        private final Socket client;
        private HashSet<String> blacklist;

        public RequestHandler(Socket clientSocket, HashSet<String> blacklist)
        {
            this.client = clientSocket;
            this.blacklist = blacklist;
        }

        @Override
        public void run()
        {
            System.out.println("Request received:");
            try
            {
                InputStream fromClient = client.getInputStream();
                byte[] request = new byte[1024];
                int length = fromClient.read(request);

                if(length > 0)
                {
                    String strRequest = new String(request, 0, length);
                    System.out.println(strRequest);

                    String firstLine = "";
                    Matcher m = pattern[0].matcher(strRequest);
                    if(m.find())
                        firstLine = m.group();

                    boolean blacklisted = false;
                    for(String url : blacklist)
                    {
                        if(firstLine.contains(url))
                        {
                            blacklisted = true;
                            break;
                        }
                    }

                    OutputStream toClient = client.getOutputStream();
                    if(!blacklisted)
                    {
                        m = pattern[1].matcher(strRequest);
                        String hostName = "";
                        int port = 80;

                        if(m.find())
                        {
                            String[] hostPort = m.group().split(":");
                            hostName = hostPort[0];

                            if(hostPort.length > 1)
                            {
                                port = Integer.parseInt(hostPort[1]);
                            }
                            else if(strRequest.contains("https://"))
                            {
                                port = 443;
                            }

//                            SSLSocketFactory factory = (SSLSocketFactory)SSLSocketFactory.getDefault();
//                            SSLSocket server = (SSLSocket)factory.createSocket(hostName, port);
//                            server.startHandshake();

                            Socket server = new Socket(hostName, port);
                            OutputStream toServer = server.getOutputStream();
                            toServer.write(request);

                            InputStream fromServer = server.getInputStream();
                            byte[] response = new byte[4096];
                            int chunkLength;

                            while((chunkLength = fromServer.read(response)) != -1)
                            {
                                toClient.write(response, 0, chunkLength);
                            }

                            toServer.close();
                            fromServer.close();
                            server.close();
                        }
                    }
                    else
                    {
                        toClient.write("This url has been blocked".getBytes());
                    }
                    toClient.close();
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

        // Listen for incoming packets and inform receivers
        @Override
        public void run() {

            try {
                // Endless loop: attempt to receive packet, notify receivers, etc
                while(true)
                {
                    Socket client = null;
                    try
                    {
                        client = socket.accept();
                        new RequestHandler(client, blacklist).start();
                    }
                    catch(Exception ignored) {}
                }
            }
            catch(Exception e) {e.printStackTrace();}
        }
    }

    static class Blocker extends Thread {

        HashSet<String> blacklist;
        Blocker(HashSet<String> blacklist)
        {
            this.blacklist = blacklist;
        }

        @Override
        public void run()
        {
            Scanner input = new Scanner(System.in);
            while(true)
            {
                String url = input.nextLine();
                System.out.printf("Are you sure you want to block \"%s\"? (y/n):\n", url);

                boolean valid = false;
                while(!valid)
                {
                    if(input.nextLine().equalsIgnoreCase("y"))
                    {
                        blacklist.add(url);
                        System.out.println("URL blocked.");
                        valid = true;
                    }
                    else if(input.nextLine().equalsIgnoreCase("n"))
                    {
                        System.out.println("URL not blocked.");
                        valid = true;
                    }
                    else
                    {
                        System.out.println("Enter y or n:");
                    }
                }
            }
        }
    }

    private synchronized void start() throws InterruptedException
    {
        listener.start();
        (new Blocker(blacklist)).start();

        System.out.println("Proxy Server running. A URL can be entered at any time to block it.");
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

