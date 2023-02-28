import java.net.*;

public class Main {

    public static void main(String[] args) throws UnknownHostException, MalformedURLException {
        InetAddress test = InetAddress.getByName("www.google.com");
        URL url = new URL("HTTP://www.google.com");
        System.out.println(url);
    }
}