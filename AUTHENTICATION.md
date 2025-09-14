# NSCR Authentication

NSCR supports optional HTTP Basic Authentication to secure your Docker registry when exposing it externally.

## Configuration

Authentication is controlled by environment variables:

- `NSCR_AUTH_ENABLED`: Set to `true` to enable authentication (default: `false`)
- `NSCR_AUTH_USERNAME`: Username for authentication (required when auth is enabled)
- `NSCR_AUTH_PASSWORD`: Password for authentication (required when auth is enabled)

## Usage

### Without Authentication (Default)

By default, NSCR runs without authentication:

```bash
# Start NSCR without authentication
docker run -p 7000:7000 nscr
```

### With Authentication

To enable authentication, set the required environment variables:

```bash
# Start NSCR with authentication
docker run -p 7000:7000 \
  -e NSCR_AUTH_ENABLED=true \
  -e NSCR_AUTH_USERNAME=admin \
  -e NSCR_AUTH_PASSWORD=secret123 \
  nscr
```

### Docker Compose Example

```yaml
version: '3.8'
services:
  nscr:
    image: nscr
    ports:
      - "7000:7000"
    environment:
      - NSCR_AUTH_ENABLED=true
      - NSCR_AUTH_USERNAME=admin
      - NSCR_AUTH_PASSWORD=secret123
    volumes:
      - nscr_data:/data
volumes:
  nscr_data:
```

## Client Usage

### Docker Login

Once authentication is enabled, clients need to authenticate:

```bash
# Login to the registry
docker login localhost:7000
# Enter username: admin
# Enter password: secret123
```

### Using with Docker Commands

After logging in, you can use Docker commands normally:

```bash
# Pull an image
docker pull localhost:7000/myapp:latest

# Push an image
docker push localhost:7000/myapp:latest
```

### Using with curl

You can also authenticate directly with curl:

```bash
# Test authentication
curl -u admin:secret123 http://localhost:7000/v2/

# List repositories
curl -u admin:secret123 http://localhost:7000/v2/_catalog
```

## Security Considerations

1. **Use HTTPS in Production**: Always use HTTPS when exposing the registry externally to prevent credential interception.

2. **Strong Passwords**: Use strong, unique passwords for production deployments.

3. **Environment Variables**: Consider using Docker secrets or other secure methods to manage credentials in production.

4. **Network Security**: Restrict network access to the registry using firewalls or network policies.

## Endpoints Protected

When authentication is enabled, the following endpoints require authentication:

- `/v2/*` - All Docker Registry API v2 endpoints
- `/api/*` - All administrative endpoints

The root endpoint `/` remains accessible without authentication for health checks.

## Troubleshooting

### Authentication Not Working

1. Verify environment variables are set correctly:
   ```bash
   docker exec <container> env | grep NSCR_AUTH
   ```

2. Check the server logs for authentication errors:
   ```bash
   docker logs <container>
   ```

3. Test authentication manually:
   ```bash
   curl -v -u username:password http://localhost:7000/v2/
   ```

### Common Issues

- **401 Unauthorized**: Check username and password
- **Missing WWW-Authenticate header**: Ensure the client supports Basic Auth
- **Connection refused**: Verify the registry is running and accessible
