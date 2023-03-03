import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


class WebProxy {

    final static int DEFAULT_HTTP_PORT = 80;
    private ServerSocket socket;
    private static HashSet<String> blacklist;
    private static HashMap<String, byte[]> cache;
    private static Pattern[] pattern;
    private static boolean statsMode;

    WebProxy(int localPort) {

        blacklist = new HashSet<>();        // contains blocked urls
        cache = new HashMap<>();            // contains cached sites
        pattern = initialisePatterns();     // array of patterns needed for regex
        statsMode = false;                  // if true, shows stats about http requests

        // initialise the thread that listens to the client
        Listener listener = new Listener();
        listener.setDaemon(true);
        listener.start();   // start listening for requests from browser

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
        Pattern[] patterns = new Pattern[4];
        patterns[0] = Pattern.compile(".*(?=\\R)");
        patterns[1] = Pattern.compile("(?<=CONNECT |GET ).*(?= )");
        patterns[2] = Pattern.compile("(?<=Host: ).*(?=\\R)");
        patterns[3] = Pattern.compile(".*(?=\\r\\n\\r\\n)", Pattern.DOTALL);

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
            try
            {
                // get the request from the client
                InputStream fromClient = client.getInputStream();
                byte[] request = new byte[1024];
                int length = fromClient.read(request);

                if(length > 0)
                {
                    String strRequest = new String(request, 0, length);

                    Matcher matcher;
                    if(strRequest.startsWith("POST"))
                    {
                        // removes unprintable characters at end of POST requests
                        matcher = pattern[3].matcher(strRequest);
                        if(matcher.find())
                            strRequest = matcher.group() + "\n\n";
                    }
                    System.out.print(strRequest);

                    OutputStream toClient = client.getOutputStream();
                    // get url using regex
                    String url = "";
                    matcher = pattern[1].matcher(strRequest);
                    if(matcher.find())
                        url = matcher.group();

                    // if https, get url and port and pass to https handler
                    if(strRequest.startsWith("CONNECT") && !blacklist.contains(url.split(":")[0]))
                    {
                        handleHTTPS(url, fromClient, toClient);
                    }
                    // if http, get hostname and pass to http handler
                    else if(!blacklist.contains(url))
                    {
                        matcher = pattern[2].matcher(strRequest);
                        if(matcher.find())
                            handleHTTP(matcher.group(), toClient, request, url, (strRequest.startsWith("GET")));
                        fromClient.close();
                        toClient.close();
                    }
                    else
                    {
                        System.out.printf("Client attempted to access blocked URL %s.\n\n", url);
                        toClient.write("This URL has been blocked".getBytes());
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

        // function for handling http requests
        private void handleHTTP(String hostName, OutputStream toClient, byte[] request, String url, boolean getRequest)
        {
            long startTime = 0;
            int bytesReceived = 0;
            if(statsMode && getRequest)
                startTime = System.nanoTime();

            // if page in cache, fetch it, else get from server
            boolean cached = cache.containsKey(url);
            if(cached)
            {
                ByteArrayInputStream fromCache = new ByteArrayInputStream(cache.get(url));
                pass(fromCache, toClient, null);
                System.out.println("Page fetched from cache\n");
            }
            else
            {
                try(Socket server = new Socket(hostName, DEFAULT_HTTP_PORT))
                {
                    OutputStream toServer = server.getOutputStream();
                    toServer.write(request);
                    InputStream fromServer = server.getInputStream();

                    // if GET request, pass in url and cache page, otherwise pass in null for no caching
                    bytesReceived = pass(fromServer, toClient, (getRequest)? url : null);
                }
                catch(Exception e) {e.printStackTrace();}
            }

            if(statsMode && getRequest)
            {
                String prefix = (cached)? "" : "un";
                System.out.printf("Time taken for %scached request was %d microseconds.\n",
                        prefix, (System.nanoTime() - startTime)/1000);
                System.out.printf("Number of bytes received from server for %scached request was %d.\n\n",
                        prefix, bytesReceived);
            }
        }


        // function for handling https requests
        private void handleHTTPS(String line, InputStream fromClient, OutputStream toClient)
        {
            String[] hostAndPort = line.split(":");
            String hostName = hostAndPort[0];
            int port = Integer.parseInt(hostAndPort[1]);

            try(Socket server = new Socket(hostName, port))
            {
                toClient.write("Connection established".getBytes());

                InputStream fromServer = server.getInputStream();
                OutputStream toServer = server.getOutputStream();

                // start a thread to pass data from the client to the server
                // while this thread passes data from the server to the client
                Thread helper = new Thread(() -> pass(fromClient, toServer, null));
                helper.start();
                pass(fromServer, toClient, null);

                // wait for thread to finish before closing socket
                helper.join();
            }
            catch(Exception e) {e.printStackTrace();}
        }
    }

    // passes data from an input stream to an output stream and
    // the number of bytes received from the server is returned
    // if cacheUrl is not null, the bytes are saved to cache with that url
    private static int pass(InputStream fromInput, OutputStream toOutput, String cacheUrl)
    {
        byte[] page = null;
        try
        {
            // read from input stream to a buffer and pass to the
            // output stream until all data has been transferred
            byte[] buffer = new byte[4096];
            int length;

            while((length = fromInput.read(buffer)) > 0)
            {
                toOutput.write(buffer, 0, length);
                if(fromInput.available() <= 0)
                    toOutput.flush();

                if(cacheUrl != null)
                    page = merge(page, buffer, length);
            }
            fromInput.close();
            toOutput.close();
        }
        catch(Exception ignored) {}

        int bytesReceived = 0;
        if(page != null)
        {
            bytesReceived = page.length;
            cache.put(cacheUrl, page); // add the page to the cache with the url as the key
        }
        return bytesReceived;
    }

    // simple method for merging two byte arrays
    public static byte[] merge(byte[] b1, byte[] b2, int b2Length)
    {
        if(b2 == null)
            return b1;
        if(b1 == null)
            return Arrays.copyOfRange(b2, 0, b2Length);

        byte[] b3 = new byte[b1.length + b2Length];
        System.arraycopy(b1, 0, b3, 0, b1.length);
        System.arraycopy(b2, 0, b3, b1.length, b2Length);

        return b3;
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
    private void go()
    {
        System.out.println("""
                            Proxy running.
                            In order to block a URL x, type block x.
                            To unblock a URL x, type unblock x.
                            To display blocked URLs, type blacklist.
                            To display cached requests, type cache.
                            To clear the cache, type clear.
                            To see timing and bandwidth statistics on http requests, type stats.
                            To terminate the proxy, type quit.
                            """);

        Scanner input = new Scanner(System.in);
        boolean running = true;
        String invalidMessage = "Invalid command - commands are block, unblock, stats, blacklist, cache, quit.\n";

        while(running)
        {
            // wait for input from console
            String[] line = input.nextLine().split(" ");
            String command = line[0];

            if(line.length == 1)
            {
                // terminate the program if quit typed
                switch(command)
                {
                    case "quit" -> running = false;
                    case "block", "unblock" -> System.out.println("Too few arguments entered");
                    case "stats" -> {
                        statsMode = !statsMode;
                        System.out.println((statsMode)? "Stats will be displayed on http requests.\n" : "Stats disabled.\n");
                    }
                    case "clear" -> {
                        cache.clear();
                        System.out.println("The cache has been cleared.\n");
                    }
                    case "blacklist" -> {
                        if(blacklist.isEmpty())
                            System.out.println("There are no blacklisted URLs.\n");
                        else
                        {
                            System.out.println("The following urls have been blocked: ");
                            for(String url : blacklist)
                                System.out.println(url);

                            System.out.println();
                        }
                    }
                    case "cache" -> {
                        if(cache.isEmpty())
                            System.out.println("There are no cached requests.\n");
                        else
                        {
                            System.out.println("The following urls are in the cache: ");
                            for(String url : cache.keySet())
                                System.out.println(url);

                            System.out.println();
                        }
                    }
                    default -> System.out.println(invalidMessage);
                }
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
                            cache.remove(url);  // removes the associated page from cache if present
                            System.out.println("URL blocked.\n");
                        }
                        else
                        {
                            boolean removed = blacklist.remove(url); // removes url from the blacklist if present
                            System.out.println((removed)? "URL successfully unblocked.\n" : "URL was not found in blacklist.\n");
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
                System.out.println(invalidMessage);
            }
        }
        input.close();
    }

    // Main function - creates Proxy server
    public static void main(String[] args) {

        try {
            (new WebProxy(4000)).go();
            System.out.println("Proxy terminated");
        } catch(java.lang.Exception e) {e.printStackTrace();}
    }
}

