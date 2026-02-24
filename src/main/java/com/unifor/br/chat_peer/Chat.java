package com.unifor.br.chat_peer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class Chat {

    private String userName;
    private ServerSocket serverSocket;
    private List<Socket> connections = new ArrayList<>();
    private Map<Socket, PrintWriter> writers = new HashMap<>();

    public Chat(String userName, int port) {
        this.userName = userName;
        try {
            this.serverSocket = new ServerSocket(port);
            System.out.println("O Peer " + userName + " está ouvindo na port: " + port);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void start() {
        new Thread(this::listenForConnections).start();
    }

    public void startUserInput() {
        new Thread(this::listenForUserinput).start();
    }

    private void listenForUserinput() {
        try {
            BufferedReader userInput = new BufferedReader(new InputStreamReader(System.in));
            while (true) {
                String mensagem = userInput.readLine();
                broadcastMessage(mensagem);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void broadcastMessage(String mensagem) {
        String formattedMessage = userName + ": " + mensagem;
        for (Socket socket : connections) {
            PrintWriter writer = writers.get(socket);
            if (writer != null) {
                writer.println(formattedMessage);
            }
        }
    }

    private void listenForConnections() {
        while (true) {
            try {
                Socket socket = serverSocket.accept();
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                synchronized (this) {
                    connections.add(socket);
                    writers.put(socket, writer);
                }
                new Thread(() -> handleConnection(socket)).start();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String mensagem;
            while ((mensagem = in.readLine()) != null) {
                System.out.println(mensagem);
            }
        } catch (IOException e) {
            System.err.println("Conexão perdida");
        } finally {
            synchronized (this) {
                connections.remove(socket);
                writers.remove(socket);
            }
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void connectionToPeer(String host, int port) {
        try {
            Socket socket = new Socket(host, port);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            synchronized (this) {
                connections.add(socket);
                writers.put(socket, writer);
            }
            new Thread(() -> handleConnection(socket)).start();
            System.out.println("Conectado a um peer em: " + host + ":" + port);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("Digite o nome do usuário:");
        String userName = scanner.nextLine();
        System.out.println("Digite a porta para escutar:");
        int port = Integer.parseInt(scanner.nextLine());

        Chat peer = new Chat(userName, port);
        peer.start();

        while (true) {
            System.out.println("Deseja conectar a outro peer? (S/N):");
            String resposta = scanner.nextLine();
            if (resposta.equalsIgnoreCase("s")) {
                System.out.println("Digite o endereço do outro host:");
                String peerHost = scanner.nextLine();
                System.out.println("Digite a porta de outro peer:");
                int peerPort = Integer.parseInt(scanner.nextLine());
                peer.connectionToPeer(peerHost, peerPort);
            } else {
                break;
            }
        }

        System.out.println("\n=== Chat iniciado! Digite suas mensagens abaixo: ===\n");
        peer.startUserInput();
    }
}