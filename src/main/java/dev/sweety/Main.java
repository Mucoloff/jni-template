package dev.sweety;

public class Main {

    public static void main(String[] args) {
        NativeApi api = new NativeApi();

        System.out.println("The sum is: " + api.sum(5, 10));

        System.out.println("The subtraction is: " + api.subtract(5, 10));
    }

}
