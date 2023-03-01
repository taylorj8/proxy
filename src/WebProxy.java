import java.io.*;
import java.net.*;
import java.util.HashSet;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


class WebProxy {

    final static int DEFAULT_HTTP_PORT = 80;
    ServerSocket socket;
    Listener listener;
    static Pattern[] pattern;
    static HashSet<String> blacklist;

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
        patterns[1] = Pattern.compile("(?<=CONNECT ).*(?= )");
        patterns[2] = Pattern.compile("(?<=Host: ).*(?=\\R)");

        return patterns;
    }

    // thread for handling requests from the client
    static class RequestHandler extends Thread {

        private final Socket client;

        public RequestHandler(Socket clientSocket)
        {
            this.client = clientSocket;
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
                        else
                        {
                            m = pattern[2].matcher(strRequest);
                            if(m.find())
                                handleHTTP(m.group(), toClient, request);
                            fromClient.close();
                            toClient.close();
                        }
                    }
                    else
                    {
                        toClient.write("This url has been blocked".getBytes());
                    }
                }
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

        private void handleHTTP(String hostName, OutputStream toClient, byte[] request)
        {
            try
            {
                Socket server = new Socket(hostName, DEFAULT_HTTP_PORT);
                OutputStream toServer = server.getOutputStream();
                toServer.write(request);

                InputStream fromServer = server.getInputStream();
                byte[] response = new byte[4096];
                int chunkLength;

                while((chunkLength = fromServer.read(response)) != -1)
                {
                    toClient.write(response, 0, chunkLength);
                }

                fromServer.close();
                toServer.close();
                server.close();

            } catch(Exception e) {e.printStackTrace();}
        }


        private void handleHTTPS(String line, InputStream fromClient, OutputStream toClient)
        {
            Socket server = null;
            try
            {
                String[] urlAndPort = line.split(":");
                String url = urlAndPort[0];
                int port = Integer.parseInt(urlAndPort[1]);

                InetAddress address = InetAddress.getByName(url);
                server = new Socket(address, port);
                server.setSoTimeout(5000);

                toClient.write("Connection established".getBytes());

                InputStream fromServer = server.getInputStream();
                OutputStream toServer = server.getOutputStream();

                // start a thread to pass data from the client to the server while
                // this thread passes data from the server to the client
                HTTPSHelper helper = new HTTPSHelper(fromClient, toServer);
                helper.start();
                pass(fromServer, toClient);

                // wait for thread to finish before closing socket
                helper.join();
            }
            catch(Exception e) {e.printStackTrace();}
            finally
            {
                try
                {
                    if(server != null)
                        server.close();
                }
                catch(Exception ignored) {}
            }
        }
    }

    // helper thread for passing data from an inputStream to and outputStream
    static class HTTPSHelper extends Thread {

        InputStream fromStream;
        OutputStream toStream;

        HTTPSHelper(InputStream fromStream, OutputStream toStream)
        {
            this.fromStream = fromStream;
            this.toStream = toStream;
        }

        @Override
        public void run()
        {
            pass(fromStream, toStream);
        }
    }

    private static void pass(InputStream in, OutputStream out)
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


    /**
     * Listener thread
     * Listens for incoming requests and hands them off to threads to begin listening again
     */
    class Listener extends Thread {

        // Listen for incoming requests
        @Override
        public void run() {

            try {

                while(true)
                {
                    Socket client = null;
                    try
                    {
                        // accept request and hand it off to a thread
                        client = socket.accept();
                        new RequestHandler(client).start();
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
        input.close();
    }

    // Main function - creates Proxy server
    public static void main(String[] args) {

        try {
            (new WebProxy(4000)).start();
            System.out.println("Proxy terminated");
        } catch(java.lang.Exception e) {e.printStackTrace();}
    }
}

