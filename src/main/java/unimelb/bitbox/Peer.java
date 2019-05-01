package unimelb.bitbox;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Console;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.sql.ClientInfoStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.net.ServerSocketFactory;
//import javax.swing.text.Document;
import javax.print.Doc;

import org.hibernate.cache.spi.support.NaturalIdNonStrictReadWriteAccess;
import org.hibernate.dialect.Teradata14Dialect;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import unimelb.bitbox.util.Configuration;
import unimelb.bitbox.util.HostPort;
import unimelb.bitbox.util.FileSystemManager.FileSystemEvent;
import unimelb.bitbox.util.Document;

public class Peer {
	private static Logger log = Logger.getLogger(Peer.class.getName());
	private static String ip = Configuration.getConfigurationValue("advertisedName");
	private static int port = Integer.parseInt(Configuration.getConfigurationValue("port"));

	// private static int maximumIncommingConnections =
	// Integer.parseInt(Configuration.getConfigurationValue("maximumIncommingConnections"));

	private static ArrayList<HostPort> connectedPeers = new ArrayList<HostPort>();
	private static ArrayList<Socket> socketList = new ArrayList<Socket>();

	public static void main(String[] args) throws IOException, NumberFormatException, NoSuchAlgorithmException {
		System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tc] %2$s %4$s: %5$s%n");
		log.info("BitBox Peer starting...");
		Configuration.getConfiguration();
		ServerMain ser = new ServerMain();

		new Thread(() -> waiting(ser)).start();

		// sleep for 2 second
		// need to read this from config
		int synchornizeTimeInterval = 2000;

		String[] peerList = Configuration.getConfigurationValue("peers").split(",");
		for (String hostPort : peerList) {
			String peerIP = hostPort.split(":")[0];
			int peerPort = Integer.parseInt(hostPort.split(":")[1]);

			Socket socket = sentConnectionRequest(peerIP, peerPort, ser);
			if ((socket != null) && (!socket.isClosed())) {
				HostPort hostport = new HostPort(peerIP, peerPort);
				new Thread(() -> peerReceiving(socket, hostport, ser)).start();
				socketList.add(socket);
				connectedPeers.add(hostport);
				System.out.println("Current connected peers: " + connectedPeers);
			}
		}

		// loop method for synchronize: sleep for a time interval (unit: second)
		sync(synchornizeTimeInterval, ser);
	}

	public static void sync(int sleepTime, ServerMain ser) {
		while (true) {
			Iterator<Socket> iter = socketList.iterator();
			while (iter.hasNext()) {
				Socket socket = iter.next();
				// check difference
				ArrayList<FileSystemEvent> pathevents = ser.fileSystemManager.generateSyncEvents();
				for(FileSystemEvent pathevent : pathevents) {
//					log.info(pathevent.toString());
					ser.processFileSystemEvent(pathevent);
				}

				// sent messages
				peerSending(socket, ser);
			}
			// clean ser
			ser.eventList.removeAll(ser.eventList);

			// sleep for a time
			try {
				TimeUnit.SECONDS.sleep(sleepTime);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}
	}

	// ==================== running the thread to receive, channel established between two sockets ====================
	public static void peerReceiving(Socket socket, HostPort hostport, ServerMain ser) {
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					DataInputStream in = new DataInputStream(socket.getInputStream());
					DataOutputStream out = new DataOutputStream(socket.getOutputStream());
					while (true) {

						try {
							TimeUnit.SECONDS.sleep(1);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}

						// to check whether the other peer is shutdown or not
						try {
							out = new DataOutputStream(socket.getOutputStream());
							Document newCommand = new Document();
							newCommand.append("command", "CHECK");
							 out.writeUTF(newCommand.toJson());
							 out.flush();
						} catch (IOException e) {
							// TODO Auto-generated catch block
							// e.printStackTrace();
							connectedPeers.remove(hostport);
							socketList.remove(socket);
							break;
						}

						// System.out.println(ser.eventList);
						// if we have message coming in
						if (in.available() > 0) {
							String received = in.readUTF();
//							System.out.println("COMMAND RECEIVED: " + received);
							Document command = Document.parse(received);
							// Handle the reply got from the server
							String message;
							switch (command.get("command").toString()) {

							case "HANDSHAKE_REQUEST":
								// here need to build a socket and start a thread: in handleHandshake
								message = handleHandshake(socket, command, ser);
								out.writeUTF(message);
								out.flush();
								System.out.println("COMMAND SENT: " + message);
								break;

							case "HANDSHAKE_RESPONSE":
								// Handshake has been successful, add this peer to the list
								HostPort connectedHostPort = new HostPort((Document) command.get("hostPort"));
								if (!connectedPeers.contains(connectedHostPort)) {
									connectedPeers.add(connectedHostPort);
									socketList.add(socket);
								}
								break;

							case "CONNECTION_REFUSED":
								// The serverPeer reached max number
								// Read the the returned peer list, use BFS to connect one of them
								socket.close();
								handleConnectionRefused(command, ser);
								// need to add stuff here
								break;

							case "FILE_MODIFY_REQUEST":
								System.out.println(command.toJson());
								// message = command.get("command").toString();
								System.out.println(
										"we need send this message to serverMain and let it modify our local file");
								break;

							case "CHECK":
								// just ignore for now
								break;

							default:
								System.out.println("Running: No matched protocol");
								break;
							}
						}
					}
				} catch (IOException e) {
					// TODO Auto-generated catch block
					// e.printStackTrace();
				}
			}

		});
		thread.start();
	}

	// ========================== sent out a connection request ===========================================
	public static Socket sentConnectionRequest(String peerIp, int peerPort, ServerMain ser) {
		Socket socket = null;
		System.out.println("Sent connection request: Try connecting to " + peerIp + ":" + peerPort);
		try {
			socket = new Socket(peerIp, peerPort);
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			Document newCommand = new Document();
			newCommand.append("command", "HANDSHAKE_REQUEST");
			// my own host port info
			HostPort hostPort = new HostPort(ip, port);
			newCommand.append("hostPort", (Document) hostPort.toDoc());
			out.writeUTF(newCommand.toJson());
			out.flush();
			System.out.println("COMMAND SENT: " + newCommand.toJson());
		} catch (UnknownHostException e) {
			System.out.println("connection failed for " + peerIp + ":" + peerPort);
			// e.printStackTrace();
		} catch (IOException e) {
			System.out.println("connection failed for " + peerIp + ":" + peerPort);
			// e.printStackTrace();
		}
		return socket;
	}

	// ================================= waiting for new connection request ==================================
	public static void waiting(ServerMain ser) {
		Thread serverListening = new Thread(new Runnable() {
			@Override
			public void run() {
				ServerSocketFactory factory = ServerSocketFactory.getDefault();
				try (ServerSocket serverSocket = factory.createServerSocket(port)) {
					System.out.println("Server listening on " + ip + ":" + port + " for a connection");
					while (true) {
						// this step will block, if there is no more connection coming in
						Socket socket = serverSocket.accept();
						DataInputStream in = new DataInputStream(socket.getInputStream());
						DataOutputStream out = new DataOutputStream(socket.getOutputStream());

						String received = in.readUTF();
						System.out.println("COMMAND RECEIVED: " + received);
						Document command = Document.parse(received);
						// Handle the reply got from the server
						String message;
						switch (command.get("command").toString()) {
						case "HANDSHAKE_REQUEST":
							// here need to build a socket and start a thread: in handleHandshake
							message = handleHandshake(socket, command, ser);
							out.writeUTF(message);
							out.flush();
							System.out.println("COMMAND SENT: " + message);
							break;
						}
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
		serverListening.start();
	}

	public static void peerSending(Socket socket, ServerMain ser) {
		try {
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			// if we have message need to send out
			Iterator<String> iter = ser.eventList.iterator();
			while (iter.hasNext()) {
				String s = iter.next();
//				System.out.println(s);
				out.writeUTF(s);
				out.flush();
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			// e.printStackTrace();
			System.out.println("e0");
		}
	}

	// ============================== helper methods =======================================================
	/**
	 * A method that generates HANDSHAKE_RESPONSE in response to HANDSHAKE_REQUEST
	 * The result is a marshaled JSONString
	 * 
	 * @param command The massage received
	 *
	 */
	private static String handleHandshake(Socket socket, Document command, ServerMain ser) {
		HostPort hostPort = new HostPort((Document) command.get("hostPort"));

		Document newCommand = new Document();

		// If connection reached max number, refuse incoming connection and send back
		// peer list
		if (connectedPeers.size() >= Integer
				.parseInt(Configuration.getConfigurationValue("maximumIncommingConnections"))) {
			newCommand.append("command", "CONNECTION_REFUSED");
			newCommand.append("message", "connection limit reached");
			ArrayList<HostPort> peers = (ArrayList<HostPort>) connectedPeers;
			ArrayList<Document> docs = new ArrayList<>();
			for (HostPort peer : peers) {
				docs.add(peer.toDoc());
			}
			
			newCommand.append("peers", docs);
			// Connection already exist, refuse it.
		} else if (connectedPeers.contains(hostPort)) {
			newCommand.append("command", "CONNECTION_EXISTS");
			newCommand.append("message", "peer already connected");
			// Able to connect, generate Handshake response
		} else {
			new Thread(() -> peerReceiving(socket, hostPort, ser)).start();

			newCommand.append("command", "HANDSHAKE_RESPONSE");
			newCommand.append("hostPort", new HostPort(ip, port).toDoc());

			// Add the connecting peer to the connected peer list
			connectedPeers.add(hostPort);
			socketList.add(socket);
			System.out.println("Current connected peers: " + connectedPeers);
			// TODO test print
			checkConnectionNumber();

		}
		return newCommand.toJson();
	}

	private static void handleConnectionRefused(Document command, ServerMain ser) {
		Queue<HostPort> hostPorts = new LinkedList<>();

		Thread t1 = new Thread(new Runnable() {
			boolean interrupted = false;

			@Override
			public void run() {
				// Add all peers in to the reconnection list
				ArrayList<Document> peers = (ArrayList<Document>) command.get("peers");
				for (Document peer : peers) {
					hostPorts.offer(new HostPort(peer));
				}
				// Connect to the peers in the queue until it's empty
				while (!hostPorts.isEmpty()) {
					HostPort hostPort = hostPorts.poll();
					
					// System.out.println(hostPorts);
					Socket socket = sentConnectionRequest(hostPort.host, hostPort.port, ser);
					if ((socket != null) && (!socket.isClosed())) {
						// Waiting reply from the peer
						try {
							DataInputStream in = new DataInputStream(socket.getInputStream());
							while (!interrupted) {
								if (in.available() > 0) {
									String received = in.readUTF();
									System.out.println("COMMAND RECEIVED" + received);
									Document reply = Document.parse(received);
									switch (reply.getString("command")) {
									case "HANDSHAKE_RESPONSE":
										// Handshake has been successful, add this peer to the list
										HostPort connectedHostPort = new HostPort((Document) reply.get("hostPort"));
										if (!connectedPeers.contains(connectedHostPort)) {
											connectedPeers.add(connectedHostPort);
											socketList.add(socket);
											hostPorts.clear();
											interrupted = true;
										}
										break;

									case "CONNECTION_REFUSED":
										peers = (ArrayList<Document>) reply.get("peers");
										if (!peers.isEmpty()) {
											for (Document peer : peers) {
												HostPort hostPort1 = new HostPort(peer);
												if (!hostPorts.contains(hostPort1)) {
													hostPorts.offer(hostPort1);
												}
											}
										}
										socket.close();
										break;

									default:
										System.out.println("No matched protocol: " + received);
										break;
									}

								}
							}

						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}

			}
			// The queue is now empty

		});
		t1.start();

	}

	private static int checkConnectionNumber() {
//		System.out.println("connectedPeers: " + connectedPeers.size());
		return connectedPeers.size();
	}

	/**
	 * @return The list of HostPort stored in configuration
	 */
	public static ArrayList<HostPort> getPeerList() {
		// Read configuration.properties
		String[] peers = Configuration.getConfigurationValue("peers").split(",");
		ArrayList<HostPort> hostPorts = new ArrayList<>();
		// Convert each string into a HostPort object
		for (String peer : peers) {
			HostPort hostPort = new HostPort(peer);
			hostPorts.add(hostPort);
		}
		return hostPorts;
	}
}
