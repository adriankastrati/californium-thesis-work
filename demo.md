# Running the Demo

## Server

```
java -jar server.jar
```

Starts a CoAP server on port `5683` with a Group OSCORE-protected observable resource at `/OSCORE-mult`. Multicast notifications are sent to `224.0.1.187:61616`.

## Client

```
java -jar client.jar <command> [host] [port]
```

| Argument  | Description       | Default        |
|-----------|-------------------|----------------|
| `command` | Client mode       | required       |
| `host`    | Server IP address | `192.168.0.33` |
| `port`    | Server CoAP port  | `5683`         |

**Commands:**

| Command         | Description                               |
|-----------------|-------------------------------------------|
| `OSCORE-mult-1` | Multicast observe as client 1 (ID `0x25`) |
| `OSCORE-mult-2` | Multicast observe as client 2 (ID `0x77`) |
| `OSCORE-mult-3` | Multicast observe as client 3 (ID `0x33`) |

### Example: 3 clients with multicast observe

Start the server, then each client in a separate terminal:

The first client triggers the group observation. All clients then receive multicast notifications on `224.0.1.187:61616`.
