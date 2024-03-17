# Introduction

The work consists of the development in Java of a chat server and a
simple client to communicate with it. The server must be based on the model
multiplex. As for the client, implement a simple graphical interface and the
client side implementation of the protocol. The client must use two threads, from
so that it can receive messages from the server while waiting for the user to
write the next message or command (otherwise it would block on reading the
socket, rendering the interface inoperable).

## Command Line

The server is implemented in a class called ChatServer and accepts the TCP port number on which it will be listening as a command line argument, for example:
`java ChatServer 8000`

The client is implemented in a class called ChatClient and accepts as command line arguments the DNS name of the server to which it wants to connect and the number of the TCP port on which the server is listening, for example:
`java ChatClient localhost 8000`

## Protocol

The communication protocol is text line oriented, i.e., each message
sent by the client to the server or by the server to the client must end with
a newline, and the message itself cannot contain changes of line.
line. Note that TCP does not do message delineation, so it is possible that
a socket read operation returns only part of a message or several
messages (the first and last being partial). It's up to the server to do
client side buffering of partially received messages.

The messages sent by the client to the server can be commands or messages
simple. Commands are of the /command format and can take separate arguments.
by spaces. Simple messages can only be sent when the user
you are in a chat room; if they start with one or more characters '/' is required
escape it by including an additional '/' character (the server must
interpret this special case, sending the other users of the room the
message without that extra character); occurrences of '/' other than in the
start of line do not need to be escaped.

The server supports the following commands:

- `/nick name`
	- Used to choose a name or to change a name. The chosen name cannot already be used by another user.
- `/join room`
	- Used to join a chat room or to change rooms. If the room does not yet exist, it is created.
- `/priv message name`
	- The command /priv name message is used to send the user name (and only the
	he) the message. If the user name does not exist, the server should return
	ERROR, otherwise it must return OK and send the user name the PRIVATE message
	sender message (where sender is the nickname of the sender the message). Note
	that they may be in different rooms, or even in none.
- `/leave`
	- Used for the user to leave the chat room he is in.
- `/bye`
	- Used to exit the chat.

Messages sent by the server to the client begin with a word in
capital letters, indicating the type of message, and may be followed by one or more
arguments, separated by spaces. The server can send the following
posts:

- `OK`
	- Used to indicate success of the command sent by the client.
- `ERROR`
	- Used to indicate failure of the command sent by the client.
- `MESSAGE message name`
	- Used to broadcast to users in a room the (simple) message sent by the user name, also in that room.
- `NEWNICK old_name new_name`
	- Used to indicate to all users in a room that the user old_name, who is in that room, changed his name to new_name.
- `JOINED name`
	- Used to indicate to users in a room that a new user, named name, has joined that room.
- `LEFT name`
	- Used to indicate to users in a room that the user named name, who was also in that room, has left.
- `BYE`
	- Used to confirm that a user who invoked the /bye command has exited.

The server maintains, associated with each client, status information, each
customer is in one of the following states:

- `init`
	- Initial status of a user who has just established the connection to the server and therefore does not yet have an associated name.
- `outside`
	- The user already has an associated name, but is not in any chat room.
- `inside`
	- The user is in a chat room, being able to send simple messages (to that room) and must receive all the messages sent by other users in that room.

