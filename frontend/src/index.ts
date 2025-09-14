// Bootstrap is loaded via CDN in the HTML template

interface RegistryStats {
  repositories: number;
  totalBlobs: number;
  totalManifests: number;
  unreferencedBlobs: number;
  estimatedSpaceToFree: number;
  lastGcRun?: string;
  logStreamClients?: number;
}

interface LogEntry {
  timestamp: number;
  level: string;
  message: string;
  logger: string;
  thread: string;
}

interface GarbageCollectionResult {
  blobsRemoved: number;
  spaceFreed: number;
  manifestsRemoved: number;
}

class RegistryWebInterface {
  private container: HTMLElement;
  private isAuthenticated = false;
  private eventSource: EventSource | null = null;
  private logs: LogEntry[] = [];
  private maxLogs = 1000;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 10; // After 10 attempts, use 5-minute intervals
  private reconnectTimeout: number | null = null;
  private isManualDisconnect = false;

  constructor(containerId: string) {
    this.container = document.getElementById(containerId)!;
    this.initializeApp();
    
    // Cleanup on page unload
    window.addEventListener('beforeunload', () => {
      this.cleanup();
    });
  }

  private cleanup() {
    this.isManualDisconnect = true;
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout);
      this.reconnectTimeout = null;
    }
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }

  private async initializeApp() {
    // Check if web auth is required
    if (await this.checkWebAuthRequired()) {
      this.showLoginForm();
    } else {
      this.showMainInterface();
    }
  }

  private async checkWebAuthRequired(): Promise<boolean> {
    try {
      const response = await fetch('/api/web/status');
      if (response.status === 401) {
        return true; // Auth required
      }
      return false; // No auth required
    } catch (error) {
      return false; // Assume no auth on error
    }
  }

  private showLoginForm() {
    this.container.innerHTML = `
      <div class="container mt-5">
        <div class="row justify-content-center">
          <div class="col-md-4">
            <div class="card">
              <div class="card-header">
                <h4 class="mb-0">Registry Login</h4>
              </div>
              <div class="card-body">
                <form id="login-form">
                  <div class="mb-3">
                    <label for="username" class="form-label">Username</label>
                    <input type="text" class="form-control" id="username" required>
                  </div>
                  <div class="mb-3">
                    <label for="password" class="form-label">Password</label>
                    <input type="password" class="form-control" id="password" required>
                  </div>
                  <button type="submit" class="btn btn-primary w-100">Login</button>
                </form>
                <div id="login-error" class="alert alert-danger mt-3" style="display: none;"></div>
              </div>
            </div>
          </div>
        </div>
      </div>
    `;

    document.getElementById('login-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      this.handleLogin();
    });
  }

  private async handleLogin() {
    const username = (document.getElementById('username') as HTMLInputElement).value;
    const password = (document.getElementById('password') as HTMLInputElement).value;
    const errorDiv = document.getElementById('login-error') as HTMLElement;

    try {
      const response = await fetch('/api/web/login', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ username, password }),
      });

      if (response.ok) {
        this.isAuthenticated = true;
        this.showMainInterface();
      } else {
        const error = await response.json();
        errorDiv.textContent = error.message || 'Login failed';
        errorDiv.style.display = 'block';
      }
    } catch (error) {
      errorDiv.textContent = 'Network error during login';
      errorDiv.style.display = 'block';
    }
  }

  private showMainInterface() {
    this.container.innerHTML = `
      <nav class="navbar navbar-expand-lg navbar-dark bg-dark">
        <div class="container">
          <a class="navbar-brand" href="#">NSCR Registry</a>
          <div class="navbar-nav ms-auto">
            <span class="navbar-text me-3">Registry Status</span>
            ${this.isAuthenticated ? '<a class="nav-link" href="#" id="logout-btn">Logout</a>' : ''}
          </div>
        </div>
      </nav>
      
      <div class="container mt-4">
        <div id="dashboard-container"></div>
        <div id="repositories-container" class="mt-4"></div>
        <div id="logs-container" class="mt-4"></div>
      </div>
    `;

    this.loadDashboard();
    this.loadRepositories();
    this.loadLogs();

    // Setup logout if authenticated
    if (this.isAuthenticated) {
      document.getElementById('logout-btn')?.addEventListener('click', () => {
        this.logout();
      });
    }
  }

  private async loadDashboard() {
    const container = document.getElementById('dashboard-container');
    if (!container) return;

    try {
      const response = await fetch('/api/web/status');
      if (!response.ok) throw new Error('Failed to load status');
      
      const stats: RegistryStats = await response.json();
      this.renderDashboard(stats);
    } catch (error) {
      container.innerHTML = `
        <div class="alert alert-danger">
          <h5>Error Loading Dashboard</h5>
          <p>Failed to load registry status: ${error}</p>
        </div>
      `;
    }
  }

  private renderDashboard(stats: RegistryStats) {
    const container = document.getElementById('dashboard-container');
    if (!container) return;

    container.innerHTML = `
      <div class="row">
        <div class="col-12">
          <h2>Registry Status</h2>
        </div>
      </div>
      
      <div class="row">
        <div class="col-md-3">
          <div class="card text-white bg-primary">
            <div class="card-body">
              <h5 class="card-title">Repositories</h5>
              <h2 class="card-text">${stats.repositories}</h2>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card text-white bg-success">
            <div class="card-body">
              <h5 class="card-title">Total Blobs</h5>
              <h2 class="card-text">${stats.totalBlobs}</h2>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card text-white bg-info">
          <div class="card-body">
              <h5 class="card-title">Total Manifests</h5>
              <h2 class="card-text">${stats.totalManifests}</h2>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card text-white bg-warning">
            <div class="card-body">
              <h5 class="card-title">Unreferenced Blobs</h5>
              <h2 class="card-text">${stats.unreferencedBlobs}</h2>
            </div>
          </div>
        </div>
      </div>
      
      <div class="row mt-4">
        <div class="col-md-6">
          <div class="card">
            <div class="card-header">
              <h5 class="mb-0">Storage Information</h5>
            </div>
            <div class="card-body">
              <p><strong>Estimated Space to Free:</strong> ${this.formatBytes(stats.estimatedSpaceToFree)}</p>
              ${stats.lastGcRun ? `<p><strong>Last GC Run:</strong> ${new Date(stats.lastGcRun).toLocaleString()}</p>` : ''}
            </div>
          </div>
        </div>
        <div class="col-md-6">
          <div class="card">
            <div class="card-header d-flex justify-content-between align-items-center">
              <h5 class="mb-0">Actions</h5>
              <button id="gc-btn" class="btn btn-warning btn-sm">
                <i class="bi bi-trash"></i> Run Garbage Collection
              </button>
            </div>
            <div class="card-body">
              <p class="text-muted">Run garbage collection to clean up unreferenced blobs and free storage space.</p>
            </div>
          </div>
        </div>
      </div>
      
      <div class="row mt-3">
        <div class="col-md-6">
          <div class="card border-danger">
            <div class="card-header bg-danger text-white">
              <h5 class="mb-0"><i class="bi bi-power"></i> Server Control</h5>
            </div>
            <div class="card-body">
              <p class="text-muted">Shutdown the server (development/testing only).</p>
              <button id="shutdown-btn" class="btn btn-danger">
                <i class="bi bi-power"></i> Shutdown Server
              </button>
            </div>
          </div>
        </div>
      </div>
    `;

    // Setup garbage collection button
    document.getElementById('gc-btn')?.addEventListener('click', () => {
      this.runGarbageCollection();
    });
    
    // Setup shutdown button
    document.getElementById('shutdown-btn')?.addEventListener('click', () => {
      this.shutdownServer();
    });
  }

  private async runGarbageCollection() {
    const btn = document.getElementById('gc-btn') as HTMLButtonElement;
    const originalText = btn.innerHTML;
    
    try {
      btn.disabled = true;
      btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> Running...';
      
      const response = await fetch('/api/garbage-collect', {
        method: 'POST',
      });
      
      if (!response.ok) throw new Error('Garbage collection failed');
      
      const result: GarbageCollectionResult = await response.json();
      
      this.showAlert(`
        <strong>Garbage Collection Completed!</strong><br>
        Blobs removed: ${result.blobsRemoved}<br>
        Manifests removed: ${result.manifestsRemoved}<br>
        Space freed: ${this.formatBytes(result.spaceFreed)}
      `, 'success');
      
      // Refresh dashboard
      this.loadDashboard();
      
    } catch (error) {
      this.showAlert(`Failed to run garbage collection: ${error}`, 'danger');
    } finally {
      btn.disabled = false;
      btn.innerHTML = originalText;
    }
  }

  private async shutdownServer() {
    // Show confirmation dialog
    const confirmed = confirm(
      'Are you sure you want to shutdown the server?\n\n' +
      'This will stop the NSCR registry server completely.\n' +
      'This action cannot be undone.'
    );
    
    if (!confirmed) {
      return;
    }
    
    const btn = document.getElementById('shutdown-btn') as HTMLButtonElement;
    const originalText = btn.innerHTML;
    
    try {
      btn.disabled = true;
      btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> Shutting down...';
      
      const response = await fetch('/api/shutdown', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        }
      });
      
      if (!response.ok) throw new Error('Shutdown request failed');
      
      const result = await response.json();
      
      this.showAlert(`
        <strong>Server Shutdown Initiated!</strong><br>
        ${result.message}<br>
        <small class="text-muted">The server will shut down in a few seconds...</small>
      `, 'warning');
      
      // Show countdown and redirect after a delay
      let countdown = 5;
      const countdownInterval = setInterval(() => {
        this.showAlert(`
          <strong>Server is shutting down...</strong><br>
          Redirecting in ${countdown} seconds
        `, 'warning');
        countdown--;
        
        if (countdown < 0) {
          clearInterval(countdownInterval);
          // Try to redirect, but the server might already be down
          window.location.href = '/';
        }
      }, 1000);
      
    } catch (error) {
      this.showAlert(`Failed to shutdown server: ${error}`, 'danger');
      btn.disabled = false;
      btn.innerHTML = originalText;
    }
  }

  private loadLogs() {
    const container = document.getElementById('logs-container');
    if (!container) return;

    container.innerHTML = `
      <div class="card">
        <div class="card-header d-flex justify-content-between align-items-center">
          <div class="d-flex align-items-center gap-2">
            <h5 class="mb-0">Live Logs</h5>
            <div id="live-indicator" class="live-indicator">
              <span class="live-dot"></span>
              <span class="live-text">LIVE</span>
            </div>
          </div>
          <div class="d-flex align-items-center gap-2">
            <span id="log-stream-status" class="badge bg-danger">
              <i class="bi bi-wifi-off"></i> Disconnected
            </span>
            <button id="start-logs-btn" class="btn btn-success btn-sm">
              <i class="bi bi-play"></i> Start
            </button>
            <button id="stop-logs-btn" class="btn btn-danger btn-sm" disabled>
              <i class="bi bi-stop"></i> Stop
            </button>
            <button id="clear-logs-btn" class="btn btn-secondary btn-sm">
              <i class="bi bi-trash"></i> Clear
            </button>
          </div>
        </div>
        <div class="card-body p-0">
          <div id="log-container" class="log-viewer">
            <div class="text-center text-muted p-3">
              <i class="bi bi-arrow-clockwise"></i> Connecting to log stream...
            </div>
          </div>
        </div>
      </div>
    `;

    // Setup event listeners
    document.getElementById('start-logs-btn')?.addEventListener('click', () => {
      this.startLogStreaming();
      this.updateButtonStates(true);
    });

    document.getElementById('stop-logs-btn')?.addEventListener('click', () => {
      this.stopLogStreaming();
      this.updateButtonStates(false);
    });

    document.getElementById('clear-logs-btn')?.addEventListener('click', () => {
      this.logs = [];
      this.updateLogDisplay();
    });

    // Auto-start log streaming when the page loads
    setTimeout(() => {
      this.startLogStreaming();
      this.updateButtonStates(true);
    }, 1000); // Small delay to ensure everything is loaded
  }

  private async loadRepositories() {
    const container = document.getElementById('repositories-container');
    if (!container) return;

    try {
      const response = await fetch('/v2/_catalog');
      if (!response.ok) throw new Error('Failed to load repositories');
      
      const data = await response.json();
      this.renderRepositories(data.repositories);
    } catch (error) {
      container.innerHTML = `
        <div class="alert alert-warning">
          <h5>Repositories</h5>
          <p>Failed to load repositories: ${error}</p>
        </div>
      `;
    }
  }

  private async renderRepositories(repositories: string[]) {
    const container = document.getElementById('repositories-container');
    if (!container) return;

    if (repositories.length === 0) {
      container.innerHTML = `
        <div class="card">
          <div class="card-header">
            <h5 class="mb-0">Repositories</h5>
          </div>
          <div class="card-body">
            <p class="text-muted">No repositories found in the registry.</p>
          </div>
        </div>
      `;
      return;
    }

    // Load tags for each repository
    const repositoryData = await Promise.all(
      repositories.map(async (repo) => {
        try {
          const response = await fetch(`/v2/${repo}/tags/list`);
          if (response.ok) {
            const data = await response.json();
            return { name: repo, tags: data.tags || [] };
          }
        } catch (error) {
          console.warn(`Failed to load tags for ${repo}:`, error);
        }
        return { name: repo, tags: [] };
      })
    );

    container.innerHTML = `
      <div class="card">
        <div class="card-header">
          <h5 class="mb-0">Repositories (${repositories.length})</h5>
        </div>
        <div class="card-body">
          <div class="row">
            ${repositoryData.map(repo => `
              <div class="col-md-6 mb-3">
                <div class="card">
                  <div class="card-body">
                    <h6 class="card-title">${repo.name}</h6>
                    <p class="card-text">
                      <span class="badge bg-secondary">${repo.tags.length} tags</span>
                    </p>
                    ${repo.tags.length > 0 ? `
                      <div class="mt-2">
                        <small class="text-muted">Latest tags:</small><br>
                        ${repo.tags.slice(0, 3).map(tag => 
                          `<span class="badge bg-light text-dark me-1">${tag}</span>`
                        ).join('')}
                        ${repo.tags.length > 3 ? `<span class="text-muted">+${repo.tags.length - 3} more</span>` : ''}
                      </div>
                    ` : ''}
                  </div>
                </div>
              </div>
            `).join('')}
          </div>
        </div>
      </div>
    `;
  }

  private formatBytes(bytes: number): string {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  }

  private showAlert(message: string, type: string) {
    const alertDiv = document.createElement('div');
    alertDiv.className = `alert alert-${type} alert-dismissible fade show`;
    alertDiv.innerHTML = `
      ${message}
      <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
    `;
    
    const container = document.getElementById('dashboard-container');
    if (container) {
      container.insertBefore(alertDiv, container.firstChild);
      
      // Auto-dismiss after 5 seconds
      setTimeout(() => {
        if (alertDiv.parentNode) {
          alertDiv.remove();
        }
      }, 5000);
    }
  }

  private logout() {
    this.isAuthenticated = false;
    this.stopLogStreaming();
    this.initializeApp();
  }

  private startLogStreaming() {
    if (this.eventSource) {
      this.stopLogStreaming();
    }

    this.isManualDisconnect = false;
    this.eventSource = new EventSource('/api/logs/stream');
    
    this.eventSource.addEventListener('connected', (event) => {
      console.log('Connected to log stream');
      this.reconnectAttempts = 0; // Reset attempts on successful connection
      this.updateLogStreamStatus(true);
      this.updateLiveIndicator(true);
      this.updateReconnectStatus('Connected');
    });

    this.eventSource.addEventListener('log', (event) => {
      const logEntry: LogEntry = JSON.parse(event.data);
      this.addLogEntry(logEntry);
    });

    this.eventSource.onerror = (error) => {
      console.error('Log stream error:', error);
      this.updateLogStreamStatus(false);
      this.updateLiveIndicator(false);
      
      if (!this.isManualDisconnect) {
        this.scheduleReconnect();
      }
    };
  }

  private stopLogStreaming() {
    this.isManualDisconnect = true;
    
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout);
      this.reconnectTimeout = null;
    }
    
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
      this.updateLogStreamStatus(false);
      this.updateLiveIndicator(false);
      this.updateReconnectStatus('Disconnected');
    }
  }

  private addLogEntry(logEntry: LogEntry) {
    this.logs.unshift(logEntry);
    
    // Keep only the most recent logs
    if (this.logs.length > this.maxLogs) {
      this.logs = this.logs.slice(0, this.maxLogs);
    }
    
    this.updateLogDisplay();
  }

  private updateLogDisplay() {
    const logContainer = document.getElementById('log-container');
    if (!logContainer) return;

    const logHtml = this.logs.map(log => {
      const timestamp = new Date(log.timestamp).toLocaleTimeString();
      const levelClass = this.getLevelClass(log.level);
      
      return `
        <div class="log-entry ${levelClass}">
          <span class="log-timestamp">${timestamp}</span>
          <span class="log-level">${log.level}</span>
          <span class="log-logger">${log.logger}</span>
          <span class="log-message">${this.escapeHtml(log.message)}</span>
        </div>
      `;
    }).join('');

    logContainer.innerHTML = logHtml;
    
    // Auto-scroll to top (newest logs)
    logContainer.scrollTop = 0;
  }

  private getLevelClass(level: string): string {
    switch (level.toUpperCase()) {
      case 'ERROR': return 'log-error';
      case 'WARN': return 'log-warn';
      case 'INFO': return 'log-info';
      case 'DEBUG': return 'log-debug';
      case 'TRACE': return 'log-trace';
      default: return 'log-default';
    }
  }

  private escapeHtml(text: string): string {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  private updateLogStreamStatus(connected: boolean) {
    const statusElement = document.getElementById('log-stream-status');
    if (statusElement) {
      if (connected) {
        statusElement.innerHTML = '<i class="bi bi-wifi"></i> Connected';
        statusElement.className = 'badge bg-success';
      } else {
        statusElement.innerHTML = '<i class="bi bi-wifi-off"></i> Disconnected';
        statusElement.className = 'badge bg-danger';
      }
    }
  }

  private updateLiveIndicator(live: boolean) {
    const indicator = document.getElementById('live-indicator');
    if (indicator) {
      if (live) {
        indicator.className = 'live-indicator live-active';
      } else {
        indicator.className = 'live-indicator live-inactive';
      }
    }
  }

  private updateButtonStates(streaming: boolean) {
    const startBtn = document.getElementById('start-logs-btn') as HTMLButtonElement;
    const stopBtn = document.getElementById('stop-logs-btn') as HTMLButtonElement;
    
    if (startBtn && stopBtn) {
      startBtn.disabled = streaming;
      stopBtn.disabled = !streaming;
    }
  }

  private scheduleReconnect() {
    if (this.isManualDisconnect) {
      return; // Don't reconnect if manually disconnected
    }

    this.reconnectAttempts++;
    
    // Calculate delay: exponential backoff up to 5 minutes (300 seconds)
    let delay: number;
    if (this.reconnectAttempts <= this.maxReconnectAttempts) {
      // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s, 64s, 128s, 256s, 300s
      delay = Math.min(Math.pow(2, this.reconnectAttempts - 1) * 1000, 300000);
    } else {
      // After max attempts, use 5-minute intervals
      delay = 300000; // 5 minutes
    }

    const delaySeconds = Math.round(delay / 1000);
    console.log(`Scheduling reconnect attempt ${this.reconnectAttempts} in ${delaySeconds} seconds`);
    
    this.updateReconnectStatus(`Reconnecting in ${delaySeconds}s (attempt ${this.reconnectAttempts})`);

    this.reconnectTimeout = window.setTimeout(() => {
      if (!this.isManualDisconnect) {
        console.log(`Attempting to reconnect (attempt ${this.reconnectAttempts})`);
        this.startLogStreaming();
      }
    }, delay);
  }

  private updateReconnectStatus(message: string) {
    const statusElement = document.getElementById('log-stream-status');
    if (statusElement) {
      if (message === 'Connected') {
        statusElement.innerHTML = '<i class="bi bi-wifi"></i> Connected';
        statusElement.className = 'badge bg-success';
      } else if (message === 'Disconnected') {
        statusElement.innerHTML = '<i class="bi bi-wifi-off"></i> Disconnected';
        statusElement.className = 'badge bg-danger';
      } else if (message.startsWith('Reconnecting')) {
        statusElement.innerHTML = `<i class="bi bi-arrow-clockwise"></i> ${message}`;
        statusElement.className = 'badge bg-warning';
      }
    }
  }
}

// Initialize the application when DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
  new RegistryWebInterface('app');
});
