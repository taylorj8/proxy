import java.io.*;
import java.net.*;
import java.util.HashSet;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


class WebProxy {

    ServerSocket socket;
    Listener listener;
    static Pattern[] pattern;
    HashSet<String> blacklist;

    WebProxy(int localPort) {

        // contains blocked urls
        blacklist = new HashSet<>();
        // array of patterns needed for regex
        pattern = initialisePatterns();

        // initialise the thread that listens to the client
        listener = new Listener();
        listener.setDaemon(true);

        try
        {
            // start server socket on the specified port
            socket = new ServerSocket(localPort);
        }
        catch(java.lang.Exception e) {e.printStackTrace();}
    }

    // add patterns to array
    private Pattern[] initialisePatterns()
    {
        Pattern[] patterns = new Pattern[3];
        patterns[0] = Pattern.compile(".*(?=\\R)");
        patterns[1] = Pattern.compile("(?<=Host: ).*(?=\\R)");
        patterns[2] = Pattern.compile("(?<=CONNECT ).*(?= )");

        return patterns;
    }

    // thread for handling requests from the client
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
                        if(firstLine.startsWith("CONNECT"))
                        {
                            m = pattern[1].matcher(strRequest);
                            if(m.find())
                                handleHTTPS(m.group(), fromClient, toClient);
                        }



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

//                            toServer.close();
//                            fromServer.close();
//                            server.close();
                        }
                    }
                    else
                    {
                        toClient.write("This url has been blocked".getBytes());
                    }
                    //todo close
//                    toClient.close();
                }
//                fromClient.close();
            }
            catch(IOException e) {e.printStackTrace();}
            finally
            {
//                try
//                {
//                    client.close();
//                }
//                catch(IOException e) {e.printStackTrace();}
            }
        }

        private void handleHTTPS(String line, InputStream fromClient, OutputStream toClient)
        {
            try
            {
                String[] urlAndPort = line.split(":");
                String url = urlAndPort[0];
                int port = Integer.parseInt(urlAndPort[1]);

//                if(!url.startsWith("http://"))
//                    url = "http://" + url;

                InetAddress address = InetAddress.getByName(url);
                Socket server = new Socket(address, port);
                server.setSoTimeout(5000);

                toClient.write("Connection established".getBytes());

                InputStream fromServer = server.getInputStream();
                OutputStream toServer = server.getOutputStream();

                (new HTTPSTransfer(fromClient, toServer)).start();
                (new HTTPSTransfer(fromServer, toClient)).start();

            }
            catch(Exception e) {e.printStackTrace();}


        }
    }


    static class HTTPSTransfer extends Thread {

        InputStream in;
        OutputStream out;

        HTTPSTransfer(InputStream in, OutputStream out)
        {
            this.in = in;
            this.out = out;
        }

        @Override
        public void run()
        {
            try
            {
                byte[] buffer  = new byte[4096];
                int length;
                while((length = in.read(buffer)) > 0)
                {
                    out.write(buffer, 0, length);
                    if(in.available() <= 0)
                        out.flush();
                }
                in.close();
                out.close();
            }
            catch(Exception ignored) {}
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

    // starts threads and waits for input on the console
    private void start()
    {
        // start listening for requests from browser
        listener.start();
        System.out.println("""
                            Proxy running.
                            In order to block a URL x, type block x.
                            To unblock a URL x, type unblock x.
                            To terminate the proxy, type quit.
                            """);

        Scanner input = new Scanner(System.in);
        boolean running = true;
        while(running)
        {
            // wait for input from console
            String[] line = input.nextLine().split(" ");
            String command = line[0];

            if(line.length == 1)
            {
                // terminate the program if quit typed
                if(command.equalsIgnoreCase("quit"))
                    running = false;
                else if(command.equals("block") || command.equals("unblock"))
                    System.out.println("Too few arguments entered");
                else
                    System.out.println("Invalid command - commands are block, unblock, quit.");
            }
            else if(line.length > 2)
            {
                System.out.println("Too many arguments entered");
            }
            else if(command.equals("block") || command.equals("unblock"))
            {
                String url = line[1];
                System.out.printf("Are you sure you want to %s \"%s\"? (y/n):\n", command, url);

                boolean valid = false;
                while(!valid)
                {
                    if(input.nextLine().equalsIgnoreCase("y"))
                    {
                        if(command.equals("block"))
                        {
                            blacklist.add(url); // adds url to the blacklist
                            System.out.println("URL blocked.");
                        }
                        else
                        {
                            boolean removed = blacklist.remove(url); // removes url from the blacklist if present
                            System.out.println(removed? "URL successfully unblocked." : "URL was not found in blacklist.");
                        }
                        valid = true;
                    }
                    else if(input.nextLine().equalsIgnoreCase("n"))
                    {
                        System.out.printf("URL not %sed.\n", command);
                        valid = true;
                    }
                    else
                    {
                        System.out.println("Enter y or n:");
                    }
                }
            }
            else
            {
                System.out.println("Invalid command - commands are block, unblock, quit.");
            }
        }
    }

    // Main function - creates Proxy server
    public static void main(String[] args) {

        try {
            (new WebProxy(4000)).start();
            System.out.println("Proxy terminated");
        } catch(java.lang.Exception e) {e.printStackTrace();}
    }
}

