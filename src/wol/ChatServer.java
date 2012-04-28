/*
 * Copyright (c) 2012 Toni Spets <toni.spets@iki.fi>
 * 
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package wol;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.regex.Pattern;
import wol.ChatChannel.GameFullException;
import wol.ChatChannel.InvalidKeyException;
import wol.ChatChannel.NoSuchUserException;
import wol.ChatChannel.UserBannedException;
import wol.ChatChannel.UserExistsException;
import static wol.ChatServer.NumericReplies.*;

/**
 *
 * @author Toni Spets
 */
public class ChatServer extends TCPServer {

    public class NumericReplies {
        final static public int RPL_LISTSTART           = 321;
        final static public int RPL_LISTGAME            = 326;
        final static public int RPL_LIST                = 327;
        final static public int RPL_CODEPAGE            = 328;
        final static public int RPL_CODEPAGESET         = 329;
        final static public int RPL_ENDOFLIST           = 323;
        final static public int RPL_TOPIC               = 332;
        final static public int RPL_NAMREPLY            = 353;
        final static public int RPL_ENDOFNAMES          = 366;
        final static public int RPL_MOTDSTART           = 375;
        final static public int RPL_MOTD                = 372;
        final static public int RPL_ENDOFMOTD           = 376;
        final static public int ERR_NOSUCHNICK          = 401;
        final static public int ERR_NOSUCHCHANNEL       = 403;
        final static public int ERR_USERNOTINCHANNEL    = 441;
        final static public int ERR_NOTONCHANNEL        = 442;
        final static public int ERR_NEEDMOREPARAMS      = 461;
        final static public int ERR_ALREADYREGISTERED   = 462;
        final static public int ERR_PASSWDMISMATCH      = 464;
        final static public int ERR_CHANNELISFULL       = 471;
        final static public int ERR_BANNEDFROMCHAN      = 474;
        final static public int ERR_BADCHANNELKEY       = 475;
    }

    Pattern ircPattern;
    String name;

    HashMap<String, ChatChannel> channels;
    ArrayList<ChatClient> clients;

    protected ChatServer(InetAddress address, int port, Selector selector) throws IOException {
        super(address, port, selector);
        name = java.net.InetAddress.getLocalHost().getHostName();
        ircPattern = Pattern.compile("^(:([^ ]+) )?([^ ]+) ?(.*)");

        channels = new HashMap<String, ChatChannel>();
        channels.put("#Lob_21_0", new ChatChannel("#Lob_21_0", "zotclot9", 21, true));
        channels.put("#Lob_21_1", new ChatChannel("#Lob_21_1", "zotclot9", 21, true));
        channels.put("#Lob_21_2", new ChatChannel("#Lob_21_2", "zotclot9", 21, true));

        clients = new ArrayList<ChatClient>();

        System.out.println("ChatServer listening on " + address + ":" + port);
    }

    void putMotd(ChatClient client) {
        putReply(client, RPL_MOTDSTART, ":- Welcome to Westwood Online!");
        putReply(client, RPL_ENDOFMOTD);
    }

    void putList(ChatClient client, int listType, int gameType) {
        putReply(client, RPL_LISTSTART);

        if (listType == 0) {
            for (Iterator<ChatChannel> i = channels.values().iterator(); i.hasNext();) {
                ChatChannel channel = i.next();
                if (channel.isPermanent() && channel.isGameType(gameType))  {
                    putReply(client, RPL_LIST, channel.getName() + " 0 0 388");
                }
            }
        }
        else if (listType == gameType) {
            for (Iterator<ChatChannel> i = channels.values().iterator(); i.hasNext();) {
                ChatChannel channel = i.next();
                if (!channel.isPermanent() && channel.getType() == gameType)  {
                    putReply(client, RPL_LISTGAME, String.format(
                        "%s %d %d %d %d %d %d %d::%s",
                        channel.getName(),
                        channel.getUsers().size(),
                        channel.getMaxUsers(),
                        channel.getType(),
                        channel.getTournament() ? 1 : 0,
                        channel.getReserved(),
                        channel.getIp(),
                        channel.getFlags(),
                        channel.getTopic()
                    ));
                }
            }
        }

        putReply(client, RPL_ENDOFLIST);
    }

    void putChannelNames(ChatClient client, ChatChannel channel) {

        ArrayList<ChatClient> clients = channel.getUsers();
        for (Iterator<ChatClient> i = clients.iterator(); i.hasNext();) {
            ChatClient c = i.next();
            // FIXME: highly inefficient, concat up to 512 bytes
            putReply(client, RPL_NAMREPLY, (channel.isPermanent() ? "* " : "= ") + channel.getName() + " :" + (channel.isOwner(c) ? "@" : "") + c.getNick() + ",0,0");
        }

        putReply(client, RPL_ENDOFNAMES, channel.getName() + " :End of names");
    }

    protected void putReply(ChatClient client, int code) {
        client.putString(":" + name + " " + code + " " + client.getNick());
    }

    protected void putReply(ChatClient client, int code, String params) {
        client.putString(":" + name + " " + code + " " + client.getNick() + " " + params);
    }

    protected void putReply(ChatClient client, String command, String params) {
        client.putString(":" + client.getNick() + "!u@h " + command + " " + params);
    }

    protected void putMessage(ChatClient from, ChatClient to, String command, String params) {
        to.putString(":" + from.getNick() + "!u@h " + command + " " + params);
    }

    protected void putReplyChannel(ChatChannel channel, ChatClient client, String command, String params) {
        putReplyChannel(channel, client, command, params, false);
    }

    protected void putReplyChannel(ChatChannel channel, ChatClient client, String command, String params, boolean skipFrom) {
        String message = ":" + client.getNick() + "!u@h " + command + " " + params;
        ArrayList<ChatClient> clients = channel.getUsers();
        for (Iterator<ChatClient> i = clients.iterator(); i.hasNext();) {
            ChatClient to = i.next();
            if (!skipFrom || to != client) {
                to.putString(message);
            }
        }
    }

    protected void putCommand(ChatClient client, String command, String params) {
        client.putString(command + " " + params);
    }

    protected void onCvers(ChatClient client, String[] params) { }

    protected void onPass(ChatClient client, String[] params) {

        if (params.length < 1) {
            putReply(client, ERR_NEEDMOREPARAMS, ":Not enough parameters");
            return;
        }

        if (!params[0].equals("supersecret")) {
            putReply(client, ERR_PASSWDMISMATCH, ":Password incorrect ("+params[0]+")");
            client.disconnect();
            return;
        }

        client.havePassword = true;
    }

    protected void onNick(ChatClient client, String[] params) {

        if (params.length < 1) {
            putReply(client, ERR_NEEDMOREPARAMS, ":Not enough parameters");
            return;
        }

        client.setNick(params[0]);
    }

    protected void onApgar(ChatClient client, String[] params) { }
    protected void onSerial(ChatClient client, String[] params) { }

    protected void onUser(ChatClient client, String[] params) {

        if (params.length < 4) {
            putReply(client, ERR_NEEDMOREPARAMS, ":Not enough parameters");
            return;
        }

        if (client.registered) {
            putReply(client, ERR_ALREADYREGISTERED, ":You have already registered");
            return;
        }

        if (!client.havePassword) {
            putReply(client, ERR_PASSWDMISMATCH, ":Password incorrect");
            client.disconnect();
            return;
        }

        if (client.getNick() != null) {
            client.registered = true;
            putMotd(client);
        }
    }

    protected void onVerchk(ChatClient client, String[] params) { }
    protected void onSetOpt(ChatClient client, String[] params) { }

    protected void onGetCodepage(ChatClient client, String[] params) {

        if (params.length < 1) {
            putReply(client, ERR_NEEDMOREPARAMS, ":Not enough parameters");
            return;
        }

        // FIXME: pull correct client encoding and not own
        String encoding = client.getEncoding();

        if (encoding.startsWith("Cp")) {
            putReply(client, RPL_CODEPAGE, client.getNick() + "`" + encoding.substring(2));
        } else {
            // FIXME: what we do when no codepage is set yet?
        }
    }
    protected void onSetCodepage(ChatClient client, String[] params) {
        try {
            client.setEncoding("Cp" + params[0]);
            putReply(client, RPL_CODEPAGESET, params[0]);
        } catch (UnsupportedEncodingException e) {
             //what is the error message?
        }
    }

    protected void onList(ChatClient client, String[] params) {
        if (params.length < 2) {
            putReply(client, ERR_NEEDMOREPARAMS, ":Not enough parameters");
            return;
        }

        putList(client, Integer.parseInt(params[0]), Integer.parseInt(params[1]));
    }

    protected void onJoinGame(ChatClient client, String[] params) {

        // normal join
        if (params.length == 3 && params[0].startsWith("#Lob_")) {
            String[] newParams = new String[2];
            newParams[0] = params[0];
            newParams[1] = params[2];
            onJoin(client, newParams);
            return;
        }

        // game join
        if (params.length == 2 || params.length == 3) {
            if (channels.containsKey(params[0])) {
                ChatChannel game = channels.get(params[0]);
                try {
                    game.join(client, params.length == 3 ? params[2] : "");
                    putReplyChannel(game, client, "JOINGAME", game.getMinUsers() + " " + game.getMaxUsers() + " " + game.getType() + " " + (game.getTournament() ? 1 : 0) + " 0 0 0 " + ":" + game.getName());
                    putReply(client, RPL_TOPIC, ":" + game.getTopic());
                    putChannelNames(client, game);
                    // handle buggy RA
                    client.sentGameopt(false);
                    client.discardQueue();
                } catch(UserExistsException e) {
                    putReply(client, "JOINGAME", game.getMinUsers() + " " + game.getMaxUsers() + " " + game.getType() + " " + (game.getTournament() ? 1 : 0) + " 0 0 0 " + ":" + game.getName());
                } catch(UserBannedException e) {
                    putReply(client, ERR_BANNEDFROMCHAN, game.getName() + " :Cannot join channel (banned)");
                } catch(GameFullException e) {
                    putReply(client, ERR_CHANNELISFULL, game.getName() + " :Cannot join channel (game is full)");
                } catch(InvalidKeyException e) {
                    putReply(client, ERR_BADCHANNELKEY, game.getName() + " :Cannot join channel (invalid key)");
                }
            } else {
                putReply(client, ERR_NOSUCHCHANNEL, params[0] + " :No such channel");
            }
        }

        // game create
        if (params.length < 8) {
            putReply(client, ERR_NEEDMOREPARAMS, ":Not enough parameters");
            return;
        }

        String name = params[0];
        String minUsers = params[1];
        String maxUsers = params[2];
        String gameType = params[3];
        String tournament = params[6];
        String reserved = params[7];
        String key = params.length > 8 ? params[8] : "";

        ChatChannel game = new ChatChannel(name, key, Integer.valueOf(gameType), false);

        game.setOwner(client);
        game.setMinUsers(Integer.valueOf(minUsers));
        game.setMaxUsers(Integer.valueOf(maxUsers));
        game.setTournament(Integer.valueOf(tournament) > 0);
        game.setReserved(Integer.valueOf(reserved));

        try {
            game.join(client, key);
            channels.put(name, game);
            putReply(client, RPL_TOPIC, ":");
            putReply(client, "JOINGAME", minUsers + " " + maxUsers + " " + gameType + " " + tournament + " 0 0 0 " + ":" + game.getName());
            putChannelNames(client, game);
            client.sentGameopt(true);
        } catch (Exception e) {
            System.out.println("Unexpected exception when joining a fresly created channel");
        }
    }

    protected void onTopic(ChatClient client, String params[]) {
        if (params.length < 2) {
            putReply(client, ERR_NEEDMOREPARAMS, ":Not enough parameters");
            return;
        }

        if (channels.containsKey(params[0])) {
            ChatChannel channel = channels.get(params[0]);
            if (channel.isOwner(client)) {
                channel.setTopic(params[1]);
            }
        } else {
            putReply(client, ERR_NOSUCHCHANNEL, params[0] + " :No such channel");
        }
    }

    protected void onGameopt(ChatClient client, String params[]) {
        if (params.length < 2) {
            putReply(client, ERR_NEEDMOREPARAMS, ":Not enough parameters");
            return;
        }

        if (params[0].startsWith("#")) {
            if (channels.containsKey(params[0])) {
                ChatChannel channel = channels.get(params[0]);
                for (Iterator<ChatClient> i = clients.iterator(); i.hasNext();) {
                    ChatClient current = i.next();
                    // handle buggy RA
                    if (!current.sentGameopt()) {
                        current.putQueue(":" + client.getNick() + "!u@h GAMEOPT " + channel.getName() + " :" + params[1]);
                    } else {
                        putMessage(client, current, "GAMEOPT", channel.getName() + " :" + params[1]);
                    }
                }
            } else {
                putReply(client, ERR_NOSUCHCHANNEL, params[0] + " :No such channel");
            }
        } else {
            for (Iterator<ChatClient> i = clients.iterator(); i.hasNext();) {
                ChatClient current = i.next();
                if (current.getNick().equals(params[0])) {
                    // handle buggy RA
                    if (!current.sentGameopt()) {
                        current.putQueue(":" + client.getNick() + "!u@h GAMEOPT " + current.getNick() + " :" + params[1]);
                    } else {
                        putMessage(client, current, "GAMEOPT", current.getNick() + " :" + params[1]);
                    }
                    client.sentGameopt(true);
                    client.flushQueue();
                    return;
                }
            }
            putReply(client, ERR_NOSUCHNICK, params[0] + " :No such nick/channel");
        }
    }

    protected void onJoin(ChatClient client, String[] params) {

        if (params.length < 2) {
            putReply(client, ERR_NEEDMOREPARAMS, ":Not enough parameters");
            return;
        }

        if (channels.containsKey(params[0])) {
            ChatChannel channel = channels.get(params[0]);
            try {
                channel.join(client, params.length > 1 ? params[1] : "");
                putReplyChannel(channel, client, "JOIN", ":0,0 " + channel.getName());
                putChannelNames(client, channel);
            } catch(UserExistsException e) {
                putReply(client, "JOIN", ":0,0 " + channel.getName());
            } catch(UserBannedException e) {
                putReply(client, ERR_BANNEDFROMCHAN, channel.getName() + " :Cannot join channel (banned)");
            } catch(GameFullException e) {
                putReply(client, ERR_CHANNELISFULL, channel.getName() + " :Cannot join channel (game is full)");
            } catch(InvalidKeyException e) {
                putReply(client, ERR_BADCHANNELKEY, channel.getName() + " :Cannot join channel (invalid key)");
            }
        } else {
            putReply(client, ERR_NOSUCHCHANNEL, params[0] + " :No such channel");
        }
    }

    protected void onPrivmsg(ChatClient client, String[] params) {

        if (params.length < 2) {
            putReply(client, ERR_NEEDMOREPARAMS, ":Not enough parameters");
            return;
        }

        if (params[0].startsWith("#")) {
            if (channels.containsKey(params[0])) {
                ChatChannel channel = channels.get(params[0]);
                putReplyChannel(channel, client, "PRIVMSG", params[0] + " :" + params[1], true);
            } else {
            }
        } else {
            putReply(client, ERR_NOSUCHNICK, params[0] + " :No such nick/channel");
        }
    }

    protected void onUserIp(ChatClient client, String[] params) {
        if (params.length < 1) {
            putReply(client, ERR_NEEDMOREPARAMS, ":Not enough parameters");
            return;
        }

        // not needed for RA
    }

    protected void onStartG(ChatClient client, String[] params) {

        if (params.length < 2) {
            putReply(client, ERR_NEEDMOREPARAMS, ":Not enough parameters");
            return;
        }

        String message = "";

        if (channels.containsKey(params[0])) {
            ChatChannel channel = channels.get(params[0]);
            if (channel.isOwner(client)) {
                for (String player : params[1].split(",")) {
                    try {
                        ChatClient user = channel.getUser(player);
                        message += user.getNick() + " " + user.getIp() + " ";
                    } catch(NoSuchUserException e) {
                        // ignore?
                    }
                }
                message+= ":1 " + (int)(System.currentTimeMillis() / 1000);

                putReplyChannel(channel, client, "STARTG", client.getNick() + " :" + message);
            }
        } else {
            putReply(client, ERR_NOSUCHCHANNEL, params[0] + " :No such channel");
        }
    }

    protected void onPart(ChatClient client, String[] params) {

        if (params.length < 1) {
            putReply(client, ERR_NEEDMOREPARAMS, ":Not enough parameters");
            return;
        }

        if (channels.containsKey(params[0])) {
            ChatChannel channel = channels.get(params[0]);
            try {
                putReply(client, "PART", channel.getName());
                channel.part(client);
                putReplyChannel(channel, client, "PART", channel.getName());
                // remove empty channel from list
                if (!channel.isPermanent() && channel.getUsers().size() == 0) {
                    channels.remove(channel.getName());
                }
            } catch(NoSuchUserException e) {
                putReply(client, ERR_NOTONCHANNEL, channel.getName() + " :You aren't on that channel");
            }
        } else {
            putReply(client, ERR_NOSUCHCHANNEL, params[0] + " :No such channel");
        }
    }

    protected void onQuit(ChatClient client, String[] params) {
        putCommand(client, "ERROR", ":Quit");
        client.disconnect();
    }

    public void clientIdle(ChatClient client) {
        putCommand(client, "PING", ":" + name);
    }

    public void clientTimeout(ChatClient client) {
        putCommand(client, "ERROR", ":Ping timeout");
        // desparately try to send the last command out
        try {
            client.canWrite();
        } catch(Exception e) {}
        client.disconnect(true);
    }

    public void clientDisconnect(ChatClient client) {
        for (Iterator<ChatClient> i = clients.iterator(); i.hasNext();) {
            ChatClient current = i.next();
            if (current == client) {
                i.remove();
            }
        }
    }

    protected void onAccept(SocketChannel clientChannel) {
        ChatClient client = new ChatClient(clientChannel, selector, this);
        clients.add(client);
    }

}