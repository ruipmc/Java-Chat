import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;


class User {
	String Username;
	SocketChannel sc;
	String Message;
	STATE State;
	String RoomId;
	
	User(String Username, SocketChannel sc) {
		this.Username = Username;
		this.sc = sc;
		this.Message = "";
		this.State = STATE.INIT;
		this.RoomId = null;
	}
}

class Room {
	String Identifier;
	Set<User> currentUsers;
	
	Room(String Name) {
		this.Identifier = Name;
		this.currentUsers = new HashSet<User>();
	}
}

enum STATE {
	INIT,
	OUTSIDE,
	INSIDE
}

public class ChatServer {
	// A pre-allocated buffer for the received data
	static private final ByteBuffer buffer = ByteBuffer.allocate(16384);

	// Decoder for incoming text -- assume UTF-8
	static private final Charset charset = Charset.forName("UTF8");
	static private final CharsetDecoder decoder = charset.newDecoder();

	static private final Map<String, Room> ListRooms = new HashMap<>();
	static private final Map<String, User> ListUsers = new HashMap<>();

	static public void main(String args[]) throws Exception {

		// Parse port from command line
		int port = Integer.parseInt(args[0]);

		try {
			// Instead of creating a ServerSocket, create a ServerSocketChannel
			ServerSocketChannel ssc = ServerSocketChannel.open();

			// Set it to non-blocking, so we can use select
			ssc.configureBlocking(false);

			// Get the Socket connected to this channel, and bind it to the
			// listening port
			ServerSocket ss = ssc.socket();
			InetSocketAddress isa = new InetSocketAddress(port);
			ss.bind(isa);

			// Create a new Selector for selecting
			Selector selector = Selector.open();

			// Register the ServerSocketChannel, so we can listen for incoming
			// connections
			ssc.register(selector, SelectionKey.OP_ACCEPT);
			System.out.println("Listening on port " + port);

			while (true) {
				// See if we've had any activity -- either an incoming connection,
				// or incoming data on an existing connection
				int num = selector.select();

				// If we don't have any activity, loop around and wait again
				if (num == 0) {
					continue;
				}

				// Get the keys corresponding to the activity that has been
				// detected, and process them one by one
				Set<SelectionKey> keys = selector.selectedKeys();
				Iterator<SelectionKey> it = keys.iterator();
				while (it.hasNext()) {
					// Get a key representing one of bits of I/O activity
					SelectionKey key = it.next();

					// What kind of activity is it?
					if (key.isAcceptable()) {

						// It's an incoming connection. Register this socket with
						// the Selector so we can listen for input on it
						Socket s = ss.accept();
						System.out.println("Got connection from " + s);

						// Make sure to make it non-blocking, so we can use a selector
						// on it.
						SocketChannel sc = s.getChannel();
						sc.configureBlocking(false);

						// Register it with the selector, for reading and attaching the new user
						sc.register(selector, SelectionKey.OP_READ, new User(null, sc));

					} else if (key.isReadable()) {

						SocketChannel sc = null;

						try {

							// It's incoming data on a connection -- process it
							sc = (SocketChannel) key.channel();
							boolean ok = processInput(sc, key);

							// If the connection is dead, remove it from the selector
							// and close it
							if (!ok) {
								removeUser(key);
								key.cancel();

								Socket s = null;
								try {
									s = sc.socket();
									System.out.println("Closing connection to " + s);
									s.close();
								} catch (IOException ie) {
									System.err.println("Error closing socket " + s + ": " + ie);
								}
							}

						} catch (IOException ie) {

							// On exception, remove this channel from the selector
							removeUser(key);
							key.cancel();

							try {
								sc.close();
							} catch (IOException ie2) {System.out.println(ie2);}

							System.out.println("Closed " + sc);
						}
					}
				}

				// We remove the selected keys, because we've dealt with them.
				keys.clear();
			}
		} catch (IOException ie) {
		  System.err.println(ie);
		}
	}

	static private boolean processInput(SocketChannel sc, SelectionKey key) throws IOException {
		// Read the message to the buffer
		buffer.clear();
		sc.read(buffer);
		buffer.flip();

		// If no data, close the connection
		if (buffer.limit() == 0) {
			return false;
		}

		String message = decoder.decode(buffer).toString();

		User currentUser = (User) key.attachment();

		if (message.charAt(message.length() - 1) != '\n') {
			currentUser.Message += message;
			return true;
		}

		processMessage(currentUser.Message + message, sc, key);
		currentUser.Message = "";

		return true;
	}

	static private final String ERROR_MESSAGE = "ERROR" + System.lineSeparator();

	static private final String OK_MESSAGE = "OK" + System.lineSeparator();

	private static void processMessage(String Message, SocketChannel sc, SelectionKey key) throws IOException {
		if (Message.length() < 2) {
			return;
		}
	
		if (Message.charAt(Message.length() - 1) == '\n') {
			Message = Message.substring(0, Message.length() - 1);
		}
	
		if (Message.charAt(0) == '/' && Message.charAt(1) != '/') {
			String[] messageSplit = Message.split(" ", 2);
	
			String command = messageSplit[0];
				
			if ("/nick".equals(command)) {
				if (messageSplit.length != 2) {
					sendMessage(sc, ERROR_MESSAGE);
				} else {
					nick(messageSplit[1], sc, key);
				}}
			
			else if ("/join".equals(command)) {
				if (messageSplit.length < 2) {
					sendMessage(sc, ERROR_MESSAGE);
				} else {
					join(messageSplit[1], sc, key);
				}} 

			else if ("/priv".equals(command)) {
				if (messageSplit.length < 2) {
					sendMessage(sc, ERROR_MESSAGE);
				} else {
					priv(messageSplit[1], sc, key);
				}} 

			else if ("/leave".equals(command)) {
				leave(sc, key, false, false);} 
			

			else if ("/bye".equals(command)) {
				bye(sc, key);
			}
	
			else {
				sendMessage(sc, ERROR_MESSAGE);
			}

		} else {
			User sender = (User) key.attachment();
	
			if (Message.charAt(0) == '/' && Message.charAt(1) == '/') {
				Message = Message.substring(1); // remove the '/'
			}
	
			if (sender.State == STATE.INSIDE) {
				String msg = "MESSAGE " + sender.Username + " " + Message + '\n';
				notifyRoom(sender.RoomId, sender.Username, msg);
			} else {
				sendMessage(sc, ERROR_MESSAGE);
			}
		}
	}
	

	static private void removeUser(SelectionKey key) throws IOException {
		if (key.attachment() == null) {
			return;
		}

		User userToRemove = (User) key.attachment();
		
		if (userToRemove.State == STATE.INIT) {
			ListUsers.remove(userToRemove.Username);
		}
		
		// If the user is inside a room, remove them from that room and notify others
		else if (userToRemove.State == STATE.INSIDE) {
			Room room = ListRooms.get(userToRemove.RoomId);
			if (room != null) {
				room.currentUsers.remove(userToRemove);
				ListUsers.remove(userToRemove.Username);
				String exitMessage = "LEFT " + userToRemove.Username + System.lineSeparator();
				notifyRoom(userToRemove.RoomId, userToRemove.Username, exitMessage);
			}
		}
	}

	
	static private void notifyRoom(String Room, String User, String Message) throws IOException {
		for (User tmp : ListRooms.get(Room).currentUsers) {
			sendMessage(ListUsers.get(tmp.Username).sc, Message);
		}
	}

	static private void sendMessage(SocketChannel sc, String Message) throws IOException {
		CharBuffer cb = CharBuffer.wrap(Message.toCharArray());
		ByteBuffer bb = charset.encode(cb);
		sc.write(bb);
	}

	static private void nick(String NewNick, SocketChannel sc, SelectionKey key) throws IOException {
		User currentUser = (User) key.attachment();

		// Check if username is taken
		if (ListUsers.containsKey(NewNick)) {
			sendMessage(sc, ERROR_MESSAGE);
			return;
		}

    	// Proceed with updating the username
		String oldUserName = currentUser.Username;
		ListUsers.remove(oldUserName);
		currentUser.Username = NewNick;
		ListUsers.put(NewNick, currentUser);

   	    // If the user is inside a room, notify the room
		if (currentUser.State == STATE.INSIDE) {
			String message = "NEWNICK " + oldUserName + " " + NewNick + System.lineSeparator();
			notifyRoom(currentUser.RoomId, currentUser.Username, message);
		} else {
			// If the user is not in a room, set their state to OUTSIDE
			currentUser.State = STATE.OUTSIDE;
		}

		sendMessage(sc, OK_MESSAGE);
	}

	static private void join(String RoomName, SocketChannel sc, SelectionKey key) throws IOException {
		User userWantJoin = (User) key.attachment();

		if (userWantJoin.State == STATE.INIT) {
			sendMessage(sc, ERROR_MESSAGE);
			return;
		}

		else {

			if (!ListRooms.containsKey(RoomName)) {
				ListRooms.put(RoomName, new Room(RoomName));
			}

			if (userWantJoin.State == STATE.OUTSIDE) {

				String message = "JOINED " + userWantJoin.Username + System.lineSeparator();
				notifyRoom(RoomName, userWantJoin.Username, message);

				ListRooms.get(RoomName).currentUsers.add(userWantJoin);
				userWantJoin.RoomId = RoomName;

				userWantJoin.State = STATE.INSIDE;
			}

			else if (userWantJoin.State == STATE.INSIDE) {

				leave(sc, key, true, false);

				String message = "JOINED " + userWantJoin.Username + System.lineSeparator();
				notifyRoom(userWantJoin.RoomId, userWantJoin.Username, message);

				ListRooms.get(RoomName).currentUsers.add(userWantJoin);
				userWantJoin.RoomId = RoomName;

			}

			sendMessage(sc, OK_MESSAGE);
		}
	}

	static private void leave(SocketChannel sc, SelectionKey key, boolean leavingToNewRoom, boolean bye) throws IOException {
		User userWantLeave = (User) key.attachment();

		if (userWantLeave.State != STATE.INSIDE) {
			sendMessage(sc, ERROR_MESSAGE);
			return;
		}

		ListRooms.get(userWantLeave.RoomId).currentUsers.remove(userWantLeave);

		String message = "LEFT " + userWantLeave.Username + System.lineSeparator();
		notifyRoom(userWantLeave.RoomId, userWantLeave.Username, message);

		if (leavingToNewRoom == false) {
			userWantLeave.State = STATE.OUTSIDE;
			userWantLeave.RoomId = null;
		}

		if (bye ==  false) {
			sendMessage(sc, OK_MESSAGE);
		}
	}

	static private void bye(SocketChannel sc, SelectionKey key) throws IOException {
		User userLeaving = (User) key.attachment();

		if (userLeaving.State == STATE.INSIDE) {
			leave(sc, key, false, true);
		}

		if (ListUsers.containsKey(userLeaving.Username)) {
			ListUsers.remove(userLeaving.Username);
		}

		sendMessage(sc, "BYE" + System.lineSeparator());
		System.out.println("Closing connection to " + sc.socket());
		sc.close();
	}

	static private void priv(String Message, SocketChannel sc, SelectionKey key) throws IOException {

		User sender = (User) key.attachment();

		if (sender.State == STATE.INIT) {
			sendMessage(sc, ERROR_MESSAGE);
			return;
		}

		String MessageSplited[] = Message.split(" ", 2);

		if (MessageSplited.length != 2) {
			sendMessage(sc, ERROR_MESSAGE);
			return;
		}

		String messageToSend = "PRIVATE " + sender.Username + " " + MessageSplited[1] + '\n';

		sendMessage(ListUsers.get(MessageSplited[0]).sc, messageToSend);
	}
}
