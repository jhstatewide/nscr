// Bootstrap is loaded via CDN in the HTML template

// Global interface for window object
declare global {
  interface Window {
    registryInterface?: RegistryWebInterface;
  }
}

// Track ongoing deletions to prevent multiple simultaneous deletions
const ongoingDeletions = new Set<string>();

export {};

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
  private statusRefreshInterval: number | null = null;
  private currentStats: RegistryStats | null = null;
  private repositoryListenersSetup = false;

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
    if (this.statusRefreshInterval) {
      clearInterval(this.statusRefreshInterval);
      this.statusRefreshInterval = null;
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
            <div class="live-indicator me-3">
              <div class="live-dot" id="live-dot"></div>
              <span class="live-text" id="live-text">OFFLINE</span>
            </div>
            <span class="navbar-text me-3">Registry Status</span>
            ${this.isAuthenticated ? '<a class="nav-link" href="#" id="logout-btn">Logout</a>' : ''}
          </div>
        </div>
      </nav>
      
      <div class="container mt-4 mb-5">
        <div id="dashboard-container"></div>
        <div id="repositories-container" class="mt-4"></div>
        <div id="logs-container" class="mt-4"></div>
      </div>
      
      <footer class="bg-light border-top mt-5 py-4">
        <div class="container">
          <div class="row">
            <div class="col-md-6">
              <h6 class="text-muted">NSCR Registry</h6>
              <p class="text-muted small mb-0">Docker Registry Management Interface</p>
            </div>
            <div class="col-md-6 text-md-end">
              <p class="text-muted small mb-0">
                <span id="footer-status">Status: <span class="text-success">Online</span></span>
              </p>
              <p class="text-muted small mb-0">
                <span id="footer-logs">Logs: <span class="text-muted">0 entries</span></span>
              </p>
            </div>
          </div>
        </div>
      </footer>
    `;

    this.loadDashboard();
    this.loadRepositories();
    this.loadLogs();
    this.startStatusRefresh();

    // Setup logout if authenticated
    if (this.isAuthenticated) {
      document.getElementById('logout-btn')?.addEventListener('click', () => {
        this.logout();
      });
    }

    // Setup action buttons
    document.addEventListener('click', (e) => {
      const target = e.target as HTMLElement;
      if (target.id === 'shutdown-btn') {
        this.shutdownServer();
      } else if (target.id === 'gc-btn') {
        this.runGarbageCollection();
      }
    });
  }

  private async loadDashboard() {
    const container = document.getElementById('dashboard-container');
    if (!container) return;

    try {
      const response = await fetch('/api/web/status');
      if (!response.ok) throw new Error('Failed to load status');
      
      const stats: RegistryStats = await response.json();
      this.currentStats = stats;
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

  private startStatusRefresh() {
    // Refresh status every 5 seconds
    this.statusRefreshInterval = window.setInterval(() => {
      this.refreshDashboard();
    }, 5000);
  }

  private async refreshDashboard() {
    try {
      const response = await fetch('/api/web/status');
      if (!response.ok) return;
      
      const newStats: RegistryStats = await response.json();
      if (this.currentStats) {
        this.updateDashboardNumbers(this.currentStats, newStats);
      }
      this.currentStats = newStats;
    } catch (error) {
      console.error('Failed to refresh dashboard:', error);
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
              <h2 class="card-text" id="stat-repositories">${stats.repositories}</h2>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card text-white bg-success">
            <div class="card-body">
              <h5 class="card-title">Total Blobs</h5>
              <h2 class="card-text" id="stat-total-blobs">${stats.totalBlobs}</h2>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card text-white bg-info">
          <div class="card-body">
              <h5 class="card-title">Total Manifests</h5>
              <h2 class="card-text" id="stat-total-manifests">${stats.totalManifests}</h2>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card text-white bg-warning">
            <div class="card-body">
              <h5 class="card-title">Unreferenced Blobs</h5>
              <h2 class="card-text" id="stat-unreferenced-blobs">${stats.unreferencedBlobs}</h2>
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
              <p><strong>Estimated Space to Free:</strong> <span data-storage-space>${this.formatBytes(stats.estimatedSpaceToFree)}</span></p>
              ${stats.lastGcRun ? `<p><strong>Last GC Run:</strong> <span data-last-gc>${new Date(stats.lastGcRun).toLocaleString()}</span></p>` : ''}
            </div>
          </div>
        </div>
        <div class="col-md-6">
          <div class="card">
            <div class="card-header">
              <h5 class="mb-0">Actions</h5>
            </div>
            <div class="card-body">
              <button class="btn btn-warning me-2" id="gc-btn">Run Garbage Collection</button>
              <button class="btn btn-danger" id="shutdown-btn">Shutdown Server</button>
            </div>
          </div>
        </div>
      </div>
    `;
  }

  private async loadRepositories() {
    const container = document.getElementById('repositories-container');
    if (!container) return;

    try {
      const response = await fetch('/v2/_catalog');
      if (!response.ok) throw new Error('Failed to load repositories');
      
      const data = await response.json();
      this.renderRepositories(data.repositories || []);
    } catch (error) {
      container.innerHTML = `
        <div class="alert alert-danger">
          <h5>Error Loading Repositories</h5>
          <p>Failed to load repositories: ${error}</p>
        </div>
      `;
    }
  }

  private renderRepositories(repositories: string[]) {
    const container = document.getElementById('repositories-container');
    if (!container) return;

    if (repositories.length === 0) {
      container.innerHTML = `
        <div class="card">
          <div class="card-body text-center">
            <h5 class="card-title">No Repositories</h5>
            <p class="card-text">No repositories have been pushed to this registry yet.</p>
          </div>
        </div>
      `;
      return;
    }

    const repositoriesHtml = repositories.map(repo => `
      <div class="card mb-3">
        <div class="card-body">
          <div class="d-flex justify-content-between align-items-center">
            <div>
              <h5 class="card-title mb-1">${this.escapeHtml(repo)}</h5>
              <small class="text-muted">Repository</small>
            </div>
            <div>
              <button class="btn btn-primary btn-sm me-2" data-action="view" data-repo="${this.escapeHtml(repo)}">View</button>
              <button class="btn btn-outline-info btn-sm me-2" data-action="copy" data-repo="${this.escapeHtml(repo)}">Copy Pull</button>
              <button class="btn btn-outline-danger btn-sm" data-action="delete" data-repo="${this.escapeHtml(repo)}">Delete</button>
            </div>
          </div>
        </div>
      </div>
    `).join('');

    container.innerHTML = `
      <div class="row">
        <div class="col-12">
          <h3>Repositories (${repositories.length})</h3>
        </div>
      </div>
      <div class="row">
        <div class="col-12">
          ${repositoriesHtml}
        </div>
      </div>
    `;

    // Add event listeners for repository action buttons
    this.setupRepositoryActionListeners();
    
    // Remove any old onclick attributes that might still be present
    this.cleanupOldOnclickAttributes();
  }

  private setupRepositoryActionListeners() {
    // Only set up listeners once to prevent duplicate event handlers
    if (this.repositoryListenersSetup) return;
    
    console.log('Setting up repository action listeners');
    
    // Use event delegation to handle all repository action buttons
    document.addEventListener('click', (e) => {
      const target = e.target as HTMLElement;
      if (target.matches('[data-action]')) {
        const action = target.getAttribute('data-action');
        const repoName = target.getAttribute('data-repo');
        
        console.log('Repository action clicked:', action, repoName);
        
        if (!repoName) return;
        
        switch (action) {
          case 'view':
            viewRepository(repoName);
            break;
          case 'copy':
            copyPullCommand(repoName);
            break;
          case 'delete':
            deleteRepository(repoName);
            break;
        }
      }
    });
    
    this.repositoryListenersSetup = true;
  }

  private cleanupOldOnclickAttributes() {
    // Remove any old onclick attributes from repository buttons
    const buttons = document.querySelectorAll('button[onclick*="deleteRepository"], button[onclick*="viewRepository"], button[onclick*="copyPullCommand"]');
    buttons.forEach(button => {
      button.removeAttribute('onclick');
    });
  }

  private loadLogs() {
    const container = document.getElementById('logs-container');
    if (!container) return;

    container.innerHTML = `
      <div class="card">
        <div class="card-header d-flex justify-content-between align-items-center">
          <h5 class="mb-0">Live Logs</h5>
          <div>
            <button class="btn btn-success btn-sm me-2" id="start-logs-btn">Start</button>
            <button class="btn btn-danger btn-sm me-2" id="stop-logs-btn" disabled>Stop</button>
            <button class="btn btn-outline-secondary btn-sm" id="clear-logs-btn">Clear</button>
          </div>
        </div>
        <div class="card-body">
          <div class="log-viewer" id="log-viewer">
            <div class="text-muted text-center">Connecting to live log stream...</div>
          </div>
        </div>
      </div>
    `;

    // Setup log control buttons
    document.getElementById('start-logs-btn')?.addEventListener('click', () => {
      this.startLogStreaming();
    });

    document.getElementById('stop-logs-btn')?.addEventListener('click', () => {
      this.stopLogStreaming();
    });

    document.getElementById('clear-logs-btn')?.addEventListener('click', () => {
      this.logs = [];
      this.updateLogDisplay();
      this.updateFooterLogCount(0);
    });

    // Auto-start log streaming
    setTimeout(() => {
      this.startLogStreaming();
    }, 1000);
  }

  private startLogStreaming() {
    if (this.eventSource) {
      return; // Already connected
    }

    this.isManualDisconnect = false;
    this.eventSource = new EventSource('/api/logs/stream');
    
    this.eventSource.onmessage = (event) => {
      try {
        const logEntry: LogEntry = JSON.parse(event.data);
        this.addLogEntry(logEntry);
      } catch (error) {
        console.error('Error parsing log entry:', error);
      }
    };

    // Listen for "log" events specifically
    this.eventSource.addEventListener('log', (event) => {
      try {
        const logEntry: LogEntry = JSON.parse(event.data);
        this.addLogEntry(logEntry);
      } catch (error) {
        console.error('Error parsing log entry:', error);
      }
    });

    this.eventSource.onerror = (error) => {
      console.error('Log stream error:', error);
      this.updateLogStreamStatus(false);
      
      if (!this.isManualDisconnect) {
        this.scheduleReconnect();
      }
    };

    this.eventSource.onopen = () => {
      console.log('Log stream connected');
      this.reconnectAttempts = 0;
      this.updateLogStreamStatus(true);
    };

    this.updateButtonStates(true);
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
    }
    
    this.updateLogStreamStatus(false);
    this.updateButtonStates(false);
  }

  private scheduleReconnect() {
    if (this.isManualDisconnect) {
      return;
    }

    this.reconnectAttempts++;
    let delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts - 1), 5 * 60 * 1000); // Max 5 minutes
    
    console.log(`Scheduling reconnect attempt ${this.reconnectAttempts} in ${delay}ms`);
    
    this.reconnectTimeout = window.setTimeout(() => {
      if (!this.isManualDisconnect) {
        this.startLogStreaming();
      }
    }, delay);
  }

  private addLogEntry(logEntry: LogEntry) {
    this.logs.unshift(logEntry);
    
    // Keep only the most recent logs
    if (this.logs.length > this.maxLogs) {
      this.logs = this.logs.slice(0, this.maxLogs);
    }
    
    this.updateLogDisplay();
  }

  private updateLogDisplay(logsToDisplay?: LogEntry[]) {
    const logViewer = document.getElementById('log-viewer');
    if (!logViewer) return;

    const logs = logsToDisplay || this.logs;
    
    if (logs.length === 0) {
      logViewer.innerHTML = '<div class="text-muted text-center">Connected - waiting for logs...</div>';
      this.updateFooterLogCount(0);
      return;
    }

    const logsHtml = logs.map((log, index) => `
      <div class="log-entry ${index === 0 ? 'newest-log' : ''}">
        <span class="log-timestamp">${new Date(log.timestamp).toLocaleString()}</span>
        <span class="log-level ${log.level}">${log.level}</span>
        <span class="log-logger">${this.escapeHtml(log.logger)}</span>
        <span class="log-message">${this.escapeHtml(log.message)}</span>
      </div>
    `).join('');

    logViewer.innerHTML = logsHtml;
    logViewer.scrollTop = 0; // Auto-scroll to top for newest logs
    
    // Update footer with log count
    this.updateFooterLogCount(logs.length);
  }

  private updateLogStreamStatus(connected: boolean) {
    const liveDot = document.getElementById('live-dot');
    const liveText = document.getElementById('live-text');
    
    if (liveDot && liveText) {
      if (connected) {
        liveDot.classList.add('connected');
        liveText.textContent = 'LIVE';
      } else {
        liveDot.classList.remove('connected');
        liveText.textContent = 'OFFLINE';
      }
    }
    
    // Update footer status
    this.updateFooterStatus(connected);
  }

  private updateFooterStatus(connected: boolean) {
    const footerStatus = document.getElementById('footer-status');
    if (footerStatus) {
      const statusSpan = footerStatus.querySelector('span');
      if (statusSpan) {
        if (connected) {
          statusSpan.textContent = 'Online';
          statusSpan.className = 'text-success';
        } else {
          statusSpan.textContent = 'Offline';
          statusSpan.className = 'text-danger';
        }
      }
    }
  }

  private updateFooterLogCount(count: number) {
    const footerLogs = document.getElementById('footer-logs');
    if (footerLogs) {
      const countSpan = footerLogs.querySelector('span');
      if (countSpan) {
        countSpan.textContent = `${count} entries`;
        countSpan.className = count > 0 ? 'text-info' : 'text-muted';
      }
    }
  }

  private updateDashboardNumbers(oldStats: RegistryStats, newStats: RegistryStats) {
    // Update repositories count
    this.animateNumberChange('stat-repositories', oldStats.repositories, newStats.repositories);
    
    // Update total blobs count
    this.animateNumberChange('stat-total-blobs', oldStats.totalBlobs, newStats.totalBlobs);
    
    // Update total manifests count
    this.animateNumberChange('stat-total-manifests', oldStats.totalManifests, newStats.totalManifests);
    
    // Update unreferenced blobs count
    this.animateNumberChange('stat-unreferenced-blobs', oldStats.unreferencedBlobs, newStats.unreferencedBlobs);
    
    // Update storage information
    this.updateStorageInfo(newStats);
  }

  private animateNumberChange(elementId: string, oldValue: number, newValue: number) {
    const element = document.getElementById(elementId);
    if (!element || oldValue === newValue) return;

    // Add transition class for smooth animation
    element.classList.add('number-transition');
    
    // Animate the number change
    const duration = 500; // 500ms animation
    const startTime = Date.now();
    const startValue = oldValue;
    const endValue = newValue;
    
    const animate = () => {
      const elapsed = Date.now() - startTime;
      const progress = Math.min(elapsed / duration, 1);
      
      // Use easing function for smooth animation
      const easeOutQuart = 1 - Math.pow(1 - progress, 4);
      const currentValue = Math.round(startValue + (endValue - startValue) * easeOutQuart);
      
      element.textContent = currentValue.toString();
      
      if (progress < 1) {
        requestAnimationFrame(animate);
      } else {
        element.classList.remove('number-transition');
      }
    };
    
    requestAnimationFrame(animate);
  }

  private updateStorageInfo(stats: RegistryStats) {
    const spaceElement = document.querySelector('[data-storage-space]');
    if (spaceElement) {
      spaceElement.textContent = this.formatBytes(stats.estimatedSpaceToFree);
    }
    
    const gcElement = document.querySelector('[data-last-gc]');
    if (gcElement && stats.lastGcRun) {
      gcElement.textContent = new Date(stats.lastGcRun).toLocaleString();
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

  private getLevelClass(level: string): string {
    return level.toLowerCase();
  }

  private escapeHtml(text: string): string {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  private async shutdownServer() {
    const confirmed = confirm('Are you sure you want to shutdown the server? This will stop the registry service.');
    if (!confirmed) return;

    try {
      const response = await fetch('/api/shutdown', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
      });

      if (response.ok) {
        // Show countdown and redirect
        document.body.innerHTML = `
          <div class="container mt-5">
            <div class="row justify-content-center">
              <div class="col-md-6">
                <div class="card">
                  <div class="card-body text-center">
                    <h4 class="card-title">Server Shutdown</h4>
                    <p class="card-text">The server is shutting down...</p>
                    <div class="spinner-border text-primary" role="status">
                      <span class="visually-hidden">Loading...</span>
                    </div>
                    <p class="mt-3">This page will redirect in <span id="countdown">5</span> seconds.</p>
                  </div>
                </div>
              </div>
            </div>
          </div>
        `;

        let countdown = 5;
        const countdownElement = document.getElementById('countdown');
        const interval = setInterval(() => {
          countdown--;
          if (countdownElement) {
            countdownElement.textContent = countdown.toString();
          }
          if (countdown <= 0) {
            clearInterval(interval);
            window.location.href = '/';
          }
        }, 1000);
      } else {
        alert('Failed to shutdown server');
      }
    } catch (error) {
      alert('Error shutting down server: ' + error);
    }
  }

  private formatBytes(bytes: number): string {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  }

  private async logout() {
    try {
      await fetch('/api/web/logout', { method: 'POST' });
      this.isAuthenticated = false;
      this.showLoginForm();
    } catch (error) {
      console.error('Logout error:', error);
    }
  }

  private async runGarbageCollection() {
    const gcButton = document.getElementById('gc-btn') as HTMLButtonElement;
    if (!gcButton) return;

    const confirmed = confirm('Run garbage collection? This will remove unreferenced blobs and free up disk space.');
    if (!confirmed) return;

    // Store original text outside try block
    const originalText = gcButton.textContent;

    try {
      // Show loading state
      gcButton.innerHTML = '<i class="spinner-border spinner-border-sm me-1"></i>Running GC...';
      gcButton.disabled = true;

      const response = await fetch('/api/garbage-collect', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
      });

      if (!response.ok) {
        throw new Error(`Garbage collection failed: ${response.statusText}`);
      }

      const result: GarbageCollectionResult = await response.json();
      
      // Show success message
      const message = `Garbage collection completed!\n\n` +
        `• Blobs removed: ${result.blobsRemoved}\n` +
        `• Manifests removed: ${result.manifestsRemoved}\n` +
        `• Space freed: ${this.formatBytes(result.spaceFreed)}`;
      
      alert(message);

      // Refresh dashboard to show updated stats
      this.loadDashboard();

    } catch (error) {
      console.error('Garbage collection error:', error);
      alert(`Garbage collection failed: ${(error as Error).message}`);
    } finally {
      // Reset button state
      gcButton.textContent = originalText;
      gcButton.disabled = false;
    }
  }
}

// Global functions for repository actions
function viewRepository(repoName: string) {
  alert(`View repository: ${repoName}\n\nThis would open a detailed view of the repository with tags and manifests.`);
}

function copyPullCommand(repoName: string) {
  const command = `docker pull localhost:7000/${repoName}`;
  navigator.clipboard.writeText(command).then(() => {
    alert('Pull command copied to clipboard!');
  }).catch(() => {
    // Fallback for older browsers
    const textArea = document.createElement('textarea');
    textArea.value = command;
    document.body.appendChild(textArea);
    textArea.select();
    document.execCommand('copy');
    document.body.removeChild(textArea);
    alert('Pull command copied to clipboard!');
  });
}

async function deleteRepository(repoName: string) {
  console.log('deleteRepository called with:', repoName, 'at', new Date().toISOString());
  
  // Prevent multiple simultaneous deletions of the same repository
  if (ongoingDeletions.has(repoName)) {
    console.log('Deletion already in progress for:', repoName);
    return;
  }
  
  ongoingDeletions.add(repoName);
  
  try {
    // Get tag count first for better confirmation
    let tagCount = 0;
    try {
      const tagsResponse = await fetch(`/v2/${encodeURIComponent(repoName)}/tags/list`);
      if (tagsResponse.ok) {
        const tagsData = await tagsResponse.json();
        tagCount = (tagsData.tags || []).length;
      }
    } catch (error) {
      console.warn('Could not get tag count for confirmation:', error as Error);
    }

    const tagInfo = tagCount > 0 ? ` (${tagCount} tag${tagCount === 1 ? '' : 's'})` : '';
    const confirmed = confirm(`Are you sure you want to delete repository "${repoName}"${tagInfo}?\n\nThis will delete all tags and manifests in this repository. This action cannot be undone.`);
    if (!confirmed) {
      ongoingDeletions.delete(repoName);
      return;
    }

    try {
    // Show loading state
    const deleteButton = document.querySelector(`[data-action="delete"][data-repo="${repoName}"]`) as HTMLElement;
    if (deleteButton) {
      deleteButton.innerHTML = '<i class="spinner-border spinner-border-sm me-1"></i>Deleting...';
      deleteButton.classList.add('disabled');
    }

    // Single API call to delete entire repository
    const deleteResponse = await fetch(`/v2/${encodeURIComponent(repoName)}`, {
      method: 'DELETE'
    });

    if (deleteResponse.ok) {
      const result = await deleteResponse.json();
      const deletedCount = result.manifestsDeleted || 0;
      
      const message = `Successfully deleted repository "${repoName}"!\n\n` +
        `• Manifests deleted: ${deletedCount}\n\n` +
        `Note: You may want to run garbage collection to free up disk space from unreferenced blobs.`;
      
      alert(message);
      
      // Refresh the repository list and dashboard
      if ((window as any).registryInterface) {
        (window as any).registryInterface.loadRepositories();
        (window as any).registryInterface.loadDashboard();
      }
      
      // Don't reset button state after successful deletion - the repository list will be refreshed
      return;
    } else if (deleteResponse.status === 404) {
      alert(`Repository "${repoName}" not found.`);
    } else {
      const errorText = await deleteResponse.text();
      throw new Error(`Failed to delete repository: ${deleteResponse.statusText} - ${errorText}`);
    }
  } catch (error) {
    console.error('Error deleting repository:', error);
    alert(`Error deleting repository "${repoName}": ${(error as Error).message}`);
  } finally {
    // Only reset button state if deletion wasn't successful (successful deletions return early)
    const deleteButton = document.querySelector(`[data-action="delete"][data-repo="${repoName}"]`) as HTMLElement;
    if (deleteButton) {
      deleteButton.innerHTML = 'Delete';
      deleteButton.classList.remove('disabled');
    }
    
    // Remove from ongoing deletions
    ongoingDeletions.delete(repoName);
  }
  } catch (error) {
    console.error('Error in deleteRepository:', error);
    ongoingDeletions.delete(repoName);
  }
}

// Initialize the app when the page loads
document.addEventListener('DOMContentLoaded', () => {
  (window as any).registryInterface = new RegistryWebInterface('app');
  
  // Make functions globally available for any remaining onclick attributes
  (window as any).viewRepository = viewRepository;
  (window as any).copyPullCommand = copyPullCommand;
  (window as any).deleteRepository = deleteRepository;
});