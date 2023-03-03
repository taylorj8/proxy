import javax.net.ssl.KeyManagerFactory;
import java.io.FileOutputStream;
import java.net.*;
import java.security.KeyStore;

public class Main {

    public static void main(String[] args) throws Exception
    {
        Test t1 = new Test(1);
        Test t2 = new Test(2);
        Test t3 = new Test(3);

        t1.start();
        t2.start();
        t3.start();
    }


    static class Test extends Thread {

        int test;

        Test(int i)
        {
            this.test = i;
        }

        @Override
        public void run()
        {
            System.out.println(test);
        }
    }
}