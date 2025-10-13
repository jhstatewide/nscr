// Import Bootstrap JavaScript
import 'bootstrap/dist/js/bootstrap.bundle.min.js';

// Global interface for window object
declare global {
  interface Window {
    registryInterface?: RegistryWebInterface;
  }
}

// Track ongoing deletions to prevent multiple simultaneous deletions
const ongoingDeletions = new Set<string>();

// Utility functions for timestamp formatting
function formatRelativeTime(timestamp: number): string {
  const now = Date.now();
  const diff = now - timestamp;

  const seconds = Math.floor(diff / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);

  if (days > 0) {
    return `${days} day${days > 1 ? 's' : ''} ago`;
  } else if (hours > 0) {
    return `${hours} hour${hours > 1 ? 's' : ''} ago`;
  } else if (minutes > 0) {
    return `${minutes} minute${minutes > 1 ? 's' : ''} ago`;
  } else {
    return 'Just now';
  }
}

function formatAbsoluteTime(timestamp: number): string {
  const date = new Date(timestamp);
  return date.toLocaleString();
}

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

interface ThroughputData {
  timestamp: number;
  categories: {
    blobUpload: { current: number; average: number; totalBytes: number };
    blobDownload: { current: number; average: number; totalBytes: number };
    manifestUpload: { current: number; average: number; totalBytes: number };
    manifestDownload: { current: number; average: number; totalBytes: number };
  };
  overall: {
    read: { current: number; average: number; totalBytes: number };
    write: { current: number; average: number; totalBytes: number };
    total: { current: number; average: number; totalBytes: number };
  };
}

interface TimeSeriesPoint {
  timestamp: number;
  readBytes: number;
  writeBytes: number;
  peakReadRate: number;
  peakWriteRate: number;
  operationCount: number;
}

interface HistoricalStats {
  timeRange: 'minute' | 'hour' | 'day';
  dataPoints: TimeSeriesPoint[];
}

class RegistryWebInterface {
  private container: HTMLElement;
  private isAuthenticated = false;
  private eventSource: EventSource | null = null;
  private repositoryEventSource: EventSource | null = null;
  private throughputEventSource: EventSource | null = null;
  private logs: LogEntry[] = [];
  private maxLogs = 500; // Reduced from 1000 to prevent memory issues
  private logDisplayUpdateThrottle: number | null = null;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 10; // After 10 attempts, use 5-minute intervals
  private reconnectTimeout: number | null = null;
  private isManualDisconnect = false;

  // Throughput tracking
  private currentThroughput: ThroughputData | null = null;
  private throughputMode: 'live' | 'hour' | 'day' = 'live';
  private throughputView: 'overall' | 'category' = 'overall';

  // Pagination state
  private allRepositories: string[] = [];
  private currentPage = 1;
  private repositoriesPerPage = 12; // Show 12 repositories per page (3 rows of 4)

  // Auto-refresh state
  private dashboardRefreshInterval: number | null = null;
  private dashboardRefreshIntervalMs = 3000; // Refresh every 3 seconds

  // Log level control
  private currentLogLevel = 'INFO';

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
    if (this.logDisplayUpdateThrottle) {
      clearTimeout(this.logDisplayUpdateThrottle);
      this.logDisplayUpdateThrottle = null;
    }
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    this.stopDashboardAutoRefresh();
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
      <nav class="navbar navbar-expand-lg navbar-dark bg-dark">
        <div class="container">
          <a class="navbar-brand" href="#">NSCR Registry</a>
          <div class="navbar-nav ms-auto">
            <span class="navbar-text me-3">Authentication Required</span>
            <div class="nav-item dropdown">
              <a class="nav-link dropdown-toggle" href="#" id="systemDropdown" role="button" data-bs-toggle="dropdown" aria-expanded="false">
                <i class="bi bi-gear"></i> System
              </a>
              <ul class="dropdown-menu dropdown-menu-end">
                <li><a class="dropdown-item" href="#" id="gc-btn"><i class="bi bi-trash text-warning"></i> Run Garbage Collection</a></li>
                <li><hr class="dropdown-divider"></li>
                <li><a class="dropdown-item" href="#" id="shutdown-btn"><i class="bi bi-power text-danger"></i> Shutdown Server</a></li>
              </ul>
            </div>
          </div>
        </div>
      </nav>

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

    // Setup system buttons for login form
    document.getElementById('gc-btn')?.addEventListener('click', () => {
      this.runGarbageCollection();
    });
    document.getElementById('shutdown-btn')?.addEventListener('click', () => {
      this.shutdownServer();
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
            <div class="nav-item dropdown">
              <a class="nav-link dropdown-toggle" href="#" id="systemDropdown" role="button" data-bs-toggle="dropdown" aria-expanded="false">
                <i class="bi bi-gear"></i> System
              </a>
              <ul class="dropdown-menu dropdown-menu-end">
                <li><a class="dropdown-item" href="#" id="gc-btn"><i class="bi bi-trash text-warning"></i> Run Garbage Collection</a></li>
                <li><hr class="dropdown-divider"></li>
                <li><a class="dropdown-item" href="#" id="shutdown-btn"><i class="bi bi-power text-danger"></i> Shutdown Server</a></li>
                ${this.isAuthenticated ? `
                  <li><hr class="dropdown-divider"></li>
                  <li><a class="dropdown-item" href="#" id="logout-btn"><i class="bi bi-box-arrow-right"></i> Logout</a></li>
                ` : ''}
              </ul>
            </div>
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

    // Start auto-refresh for dashboard stats
    this.startDashboardAutoRefresh();

    // Start repository streaming for live updates
    this.startRepositoryStreaming();

    // Start throughput streaming for live monitoring
    this.startThroughputStreaming();

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

      // Update last refresh time
      this.updateLastRefreshTime();
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
          <div class="d-flex justify-content-between align-items-center">
            <h2>Registry Status</h2>
            <div class="text-muted small">
              <i class="bi bi-arrow-clockwise"></i> Auto-refresh: <span id="last-refresh-time">--:--:--</span>
            </div>
          </div>
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
          <div id="throughput-container">
            <!-- Throughput card will be rendered here -->
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

    // Initialize throughput display
    this.updateThroughputDisplay();
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
    // Show enhanced confirmation dialog
    const confirmed = confirm(
      '⚠️  WARNING: Server Shutdown ⚠️\n\n' +
      'You are about to shutdown the NSCR registry server.\n\n' +
      'This will:\n' +
      '• Stop all registry services\n' +
      '• Disconnect all active clients\n' +
      '• Require manual restart to resume\n\n' +
      'This action cannot be undone.\n\n' +
      'Are you absolutely sure you want to continue?'
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
            <div class="d-flex align-items-center gap-1">
              <label for="log-level-select" class="form-label mb-0 small">Level:</label>
              <select id="log-level-select" class="form-select form-select-sm" style="width: auto;">
                <option value="ERROR">ERROR</option>
                <option value="WARN">WARN</option>
                <option value="INFO" selected>INFO</option>
                <option value="DEBUG">DEBUG</option>
                <option value="TRACE">TRACE</option>
              </select>
            </div>
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

    document.getElementById('log-level-select')?.addEventListener('change', (e) => {
      const target = e.target as HTMLSelectElement;
      this.setLogLevel(target.value);
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
      const response = await fetch('/api/registry/repositories');
      if (!response.ok) throw new Error('Failed to load repositories');

      const data = await response.json();
      this.allRepositories = data.repositories || [];
      this.currentPage = 1; // Reset to first page when loading new data
      this.renderRepositories();
    } catch (error) {
      container.innerHTML = `
        <div class="alert alert-warning">
          <h5>Repositories</h5>
          <p>Failed to load repositories: ${error}</p>
        </div>
      `;
    }
  }

  private async renderRepositories() {
    const container = document.getElementById('repositories-container');
    if (!container) return;

    if (this.allRepositories.length === 0) {
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

    // Calculate pagination
    const totalPages = Math.ceil(this.allRepositories.length / this.repositoriesPerPage);
    const startIndex = (this.currentPage - 1) * this.repositoriesPerPage;
    const endIndex = Math.min(startIndex + this.repositoriesPerPage, this.allRepositories.length);
    const currentRepositories = this.allRepositories.slice(startIndex, endIndex);

    // Load tags for each repository
    const repositoryData = await Promise.all(
      currentRepositories.map(async (repo) => {
        try {
          const response = await fetch(`/v2/${repo.name || repo}/tags/list`);
          if (response.ok) {
            const data = await response.json();
            return {
              name: repo.name || repo,
              tags: data.tags || [],
              lastUploaded: repo.lastUploaded || null
            };
          }
        } catch (error) {
          console.warn(`Failed to load tags for ${repo.name || repo}:`, error);
        }
        return {
          name: repo.name || repo,
          tags: [],
          lastUploaded: repo.lastUploaded || null
        };
      })
    );

    container.innerHTML = `
      <div class="card">
        <div class="card-header d-flex justify-content-between align-items-center">
          <h5 class="mb-0">
            Repositories (${this.allRepositories.length} total)
            ${this.allRepositories.length > this.repositoriesPerPage ?
              ` - Page ${this.currentPage} of ${totalPages} (${startIndex + 1}-${endIndex})` :
              ''
            }
          </h5>
          ${this.allRepositories.length > this.repositoriesPerPage ? `
            <div class="btn-group pagination-controls" role="group">
              <button type="button" class="btn btn-outline-secondary btn-sm"
                      onclick="changePage(${this.currentPage - 1})"
                      ${this.currentPage <= 1 ? 'disabled' : ''}>
                <i class="bi bi-chevron-left"></i> Previous
              </button>
              <button type="button" class="btn btn-outline-secondary btn-sm"
                      onclick="changePage(${this.currentPage + 1})"
                      ${this.currentPage >= totalPages ? 'disabled' : ''}>
                Next <i class="bi bi-chevron-right"></i>
              </button>
            </div>
          ` : ''}
        </div>
        <div class="card-body">
          <div class="row">
            ${repositoryData.map(repo => `
              <div class="col-md-6 mb-3">
                <div class="card">
                  <div class="card-body">
                    <div class="d-flex justify-content-between align-items-start">
                      <div class="flex-grow-1">
                        <h6 class="card-title">${repo.name}</h6>
                        <p class="card-text">
                          <span class="badge bg-secondary">${repo.tags.length} tags</span>
                          ${repo.lastUploaded && repo.lastUploaded > 0 ? `
                            <br><small class="text-muted" title="${formatAbsoluteTime(repo.lastUploaded)}">
                              <i class="bi bi-clock"></i> ${formatRelativeTime(repo.lastUploaded)}
                            </small>
                          ` : repo.lastUploaded === 0 ? `
                            <br><small class="text-muted">
                              <i class="bi bi-clock"></i> Upload time not available
                            </small>
                          ` : ''}
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
                      <div class="ms-2">
                        <button class="btn btn-outline-danger btn-sm" onclick="deleteRepository('${repo.name}')" title="Delete repository">
                          <i class="bi bi-trash"></i>
                        </button>
          </div>
        </div>
      </div>
        </div>
      </div>
            `).join('')}
          </div>
        </div>
      </div>
    `;
  }

  public changePage(page: number) {
    const totalPages = Math.ceil(this.allRepositories.length / this.repositoriesPerPage);
    if (page >= 1 && page <= totalPages) {
      this.currentPage = page;
      this.renderRepositories();
    }
  }

  public async deleteRepository(repositoryName: string) {
    console.log('deleteRepository called with:', repositoryName, 'at', new Date().toISOString());

    // Prevent multiple simultaneous deletions of the same repository
    if (ongoingDeletions.has(repositoryName)) {
      console.log('Deletion already in progress for:', repositoryName);
      return;
    }

    ongoingDeletions.add(repositoryName);

    try {
      // Get tag count first for better confirmation
      let tagCount = 0;
      try {
        const tagsResponse = await fetch(`/v2/${encodeURIComponent(repositoryName)}/tags/list`);
        if (tagsResponse.ok) {
          const tagsData = await tagsResponse.json();
          tagCount = (tagsData.tags || []).length;
        }
      } catch (error) {
        console.warn('Could not get tag count for confirmation:', error as Error);
      }

      const tagInfo = tagCount > 0 ? ` (${tagCount} tag${tagCount === 1 ? '' : 's'})` : '';
      const confirmed = confirm(
        `Are you sure you want to delete repository "${repositoryName}"${tagInfo}?\n\n` +
        'This will permanently delete:\n' +
        '• All tags in this repository\n' +
        '• All manifests\n' +
        '• All associated blobs (if not referenced by other repositories)\n\n' +
        'This action cannot be undone.'
      );

      if (!confirmed) {
        ongoingDeletions.delete(repositoryName);
        return;
      }

      try {
        // Show loading state
        const deleteButton = document.querySelector(`button[onclick*="deleteRepository('${repositoryName}')"]`) as HTMLElement;
        if (deleteButton) {
          deleteButton.innerHTML = '<i class="spinner-border spinner-border-sm me-1"></i>Deleting...';
          deleteButton.classList.add('disabled');
        }

        const response = await fetch(`/v2/${encodeURIComponent(repositoryName)}`, {
          method: 'DELETE',
          headers: {
            'Content-Type': 'application/json'
          }
        });

        if (response.ok) {
          const result = await response.json();
          const deletedCount = result.manifestsDeleted || 0;

          this.showAlert(`
            <strong>Repository Deleted Successfully!</strong><br>
            Repository: ${repositoryName}<br>
            Manifests deleted: ${deletedCount}<br>
            <small class="text-muted">Note: You may want to run garbage collection to free up disk space from unreferenced blobs.</small>
          `, 'success');

          // Remove the repository from our local list and adjust pagination
          this.allRepositories = this.allRepositories.filter(repo => repo !== repositoryName);

          // Adjust current page if we're now on an empty page
          const totalPages = Math.ceil(this.allRepositories.length / this.repositoriesPerPage);
          if (this.currentPage > totalPages && totalPages > 0) {
            this.currentPage = totalPages;
          }

          // Re-render the repositories list
          this.renderRepositories();

          // Refresh the dashboard to update stats
          this.loadDashboard();

          // Don't reset button state after successful deletion - the repository list will be refreshed
          return;

        } else if (response.status === 404) {
          this.showAlert(`Repository "${repositoryName}" not found`, 'warning');
          // Remove from local list and refresh in case it was already deleted
          this.allRepositories = this.allRepositories.filter(repo => repo !== repositoryName);
          const totalPages = Math.ceil(this.allRepositories.length / this.repositoriesPerPage);
          if (this.currentPage > totalPages && totalPages > 0) {
            this.currentPage = totalPages;
          }
          this.renderRepositories();
        } else {
          const errorText = await response.text();
          throw new Error(`Failed to delete repository: ${response.statusText} - ${errorText}`);
        }

      } catch (error) {
        console.error('Error deleting repository:', error);
        this.showAlert(`Failed to delete repository "${repositoryName}": ${(error as Error).message}`, 'danger');
      } finally {
        // Only reset button state if deletion wasn't successful (successful deletions return early)
        const deleteButton = document.querySelector(`button[onclick*="deleteRepository('${repositoryName}')"]`) as HTMLElement;
        if (deleteButton) {
          deleteButton.innerHTML = '<i class="bi bi-trash"></i>';
          deleteButton.classList.remove('disabled');
        }

        // Remove from ongoing deletions
        ongoingDeletions.delete(repositoryName);
      }
    } catch (error) {
      console.error('Error in deleteRepository:', error);
      ongoingDeletions.delete(repositoryName);
    }
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
    this.stopRepositoryStreaming();
    this.stopThroughputStreaming();
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

  private startRepositoryStreaming() {
    if (this.repositoryEventSource) {
      this.stopRepositoryStreaming();
    }

    this.repositoryEventSource = new EventSource('/api/repositories/stream');

    this.repositoryEventSource.addEventListener('connected', (event) => {
      console.log('Connected to repository stream');
    });

    this.repositoryEventSource.addEventListener('repository_update', (event) => {
      const update = JSON.parse(event.data);
      console.log('Repository update received:', update);
      this.handleRepositoryUpdate(update);
    });

    this.repositoryEventSource.addEventListener('repository_list_updated', (event) => {
      console.log('Repository list updated');
      this.refreshRepositories();
    });

    this.repositoryEventSource.onerror = (error) => {
      console.error('Repository stream error:', error);
    };
  }

  private stopRepositoryStreaming() {
    if (this.repositoryEventSource) {
      this.repositoryEventSource.close();
      this.repositoryEventSource = null;
    }
  }

  private handleRepositoryUpdate(update: any) {
    // Handle specific repository updates
    console.log(`Repository ${update.action}: ${update.repository}`);

    // Refresh the repositories list to show the latest data
    this.refreshRepositories();
  }

  private async refreshRepositories() {
    // Reload repositories without changing the current page
    const currentPage = this.currentPage;
    await this.loadRepositories();
    this.currentPage = currentPage;
    await this.renderRepositories();
  }

  private startThroughputStreaming() {
    if (this.throughputEventSource) {
      this.stopThroughputStreaming();
    }

    this.throughputEventSource = new EventSource('/api/throughput/stream');

    this.throughputEventSource.addEventListener('connected', (event) => {
      console.log('Connected to throughput stream');
    });

    // Set a timeout to show default state if no data is received
    setTimeout(() => {
      if (!this.currentThroughput) {
        console.log('No throughput data received, showing default state');
        this.updateThroughputDisplay();
      }
    }, 2000);

    this.throughputEventSource.addEventListener('throughput_update', (event) => {
      const throughputData: ThroughputData = JSON.parse(event.data);
      this.currentThroughput = throughputData;
      console.log('Throughput data received:', throughputData);
      this.updateThroughputDisplay();
    });

    this.throughputEventSource.addEventListener('heartbeat', (event) => {
      const heartbeatData = JSON.parse(event.data);
      console.log('Registry heartbeat - idle state:', heartbeatData.message);
      // Show idle state in the UI
      this.showIdleState();
    });

    this.throughputEventSource.onerror = (error) => {
      console.error('Throughput stream error:', error);
      // Try to reconnect after a delay
      setTimeout(() => {
        if (this.throughputEventSource?.readyState === EventSource.CLOSED) {
          console.log('Attempting to reconnect throughput stream...');
          this.startThroughputStreaming();
        }
      }, 5000);
    };
  }

  private stopThroughputStreaming() {
    if (this.throughputEventSource) {
      this.throughputEventSource.close();
      this.throughputEventSource = null;
    }
  }

  private updateThroughputDisplay() {
    const container = document.getElementById('throughput-container');
    if (!container) {
      console.log('Throughput container not found');
      return;
    }

    // If no current throughput data, show a default state
    if (!this.currentThroughput) {
      container.innerHTML = `
        <div class="throughput-card throughput-idle">
          <div class="throughput-header">
            <h6>Throughput</h6>
            <div class="throughput-controls">
              <select id="throughput-mode" class="form-select form-select-sm">
                <option value="live" ${this.throughputMode === 'live' ? 'selected' : ''}>Live</option>
                <option value="hour" ${this.throughputMode === 'hour' ? 'selected' : ''}>Hour</option>
                <option value="day" ${this.throughputMode === 'day' ? 'selected' : ''}>Day</option>
              </select>
              <select id="throughput-view" class="form-select form-select-sm">
                <option value="overall" ${this.throughputView === 'overall' ? 'selected' : ''}>Overall</option>
                <option value="category" ${this.throughputView === 'category' ? 'selected' : ''}>Category</option>
              </select>
            </div>
          </div>
          <div class="throughput-content">
            <div class="throughput-metric">
              <span class="throughput-label">● Read:</span>
              <span class="throughput-value">0.0 B/s</span>
            </div>
            <div class="throughput-metric">
              <span class="throughput-label">● Write:</span>
              <span class="throughput-value">0.0 B/s</span>
            </div>
            <div class="throughput-divider"></div>
            <div class="throughput-metric throughput-total">
              <span class="throughput-label">Total:</span>
              <span class="throughput-value">0.0 B/s</span>
              <small class="throughput-average">(5s avg)</small>
            </div>
          </div>
        </div>
      `;

      // Add event listeners for the controls
      this.setupThroughputControls();
      return;
    }

    const data = this.currentThroughput;
    const formatRate = (bytesPerSecond: number): string => {
      if (bytesPerSecond >= 1024 * 1024) {
        return `${(bytesPerSecond / (1024 * 1024)).toFixed(1)} MB/s`;
      } else if (bytesPerSecond >= 1024) {
        return `${(bytesPerSecond / 1024).toFixed(1)} kB/s`;
      } else {
        return `${bytesPerSecond.toFixed(1)} B/s`;
      }
    };

    const isActive = data.overall.total.current > 0;
    const activeClass = isActive ? 'throughput-active' : 'throughput-idle';

    if (this.throughputView === 'overall') {
      container.innerHTML = `
        <div class="throughput-card ${activeClass}">
          <div class="throughput-header">
            <h6>Network Throughput</h6>
            <div class="throughput-controls">
              <select id="throughput-mode" class="form-select form-select-sm">
                <option value="live" ${this.throughputMode === 'live' ? 'selected' : ''}>Live</option>
                <option value="hour" ${this.throughputMode === 'hour' ? 'selected' : ''}>Hour</option>
                <option value="day" ${this.throughputMode === 'day' ? 'selected' : ''}>Day</option>
              </select>
              <select id="throughput-view" class="form-select form-select-sm">
                <option value="overall" ${this.throughputView === 'overall' ? 'selected' : ''}>Overall</option>
                <option value="category" ${this.throughputView === 'category' ? 'selected' : ''}>Category</option>
              </select>
            </div>
          </div>
          <div class="throughput-content">
            <div class="throughput-metric">
              <span class="throughput-label">● Read:</span>
              <span class="throughput-value">${formatRate(data.overall.read.current)}</span>
            </div>
            <div class="throughput-metric">
              <span class="throughput-label">● Write:</span>
              <span class="throughput-value">${formatRate(data.overall.write.current)}</span>
            </div>
            <div class="throughput-divider"></div>
            <div class="throughput-metric throughput-total">
              <span class="throughput-label">Total:</span>
              <span class="throughput-value">${formatRate(data.overall.total.current)}</span>
              <small class="throughput-average">(5s avg)</small>
            </div>
          </div>
        </div>
      `;
    } else {
      container.innerHTML = `
        <div class="throughput-card ${activeClass}">
          <div class="throughput-header">
            <h6>Network Throughput</h6>
            <div class="throughput-controls">
              <select id="throughput-mode" class="form-select form-select-sm">
                <option value="live" ${this.throughputMode === 'live' ? 'selected' : ''}>Live</option>
                <option value="hour" ${this.throughputMode === 'hour' ? 'selected' : ''}>Hour</option>
                <option value="day" ${this.throughputMode === 'day' ? 'selected' : ''}>Day</option>
              </select>
              <select id="throughput-view" class="form-select form-select-sm">
                <option value="overall" ${this.throughputView === 'overall' ? 'selected' : ''}>Overall</option>
                <option value="category" ${this.throughputView === 'category' ? 'selected' : ''}>Category</option>
              </select>
            </div>
          </div>
          <div class="throughput-content">
            <div class="throughput-metric">
              <span class="throughput-label">● Blob Upload:</span>
              <span class="throughput-value">${formatRate(data.categories.blobUpload.current)}</span>
            </div>
            <div class="throughput-metric">
              <span class="throughput-label">● Blob Download:</span>
              <span class="throughput-value">${formatRate(data.categories.blobDownload.current)}</span>
            </div>
            <div class="throughput-metric">
              <span class="throughput-label">● Manifest Upload:</span>
              <span class="throughput-value">${formatRate(data.categories.manifestUpload.current)}</span>
            </div>
            <div class="throughput-metric">
              <span class="throughput-label">● Manifest Download:</span>
              <span class="throughput-value">${formatRate(data.categories.manifestDownload.current)}</span>
            </div>
          </div>
        </div>
      `;
    }

    // Add event listeners for the controls
    this.setupThroughputControls();
  }

  private async loadHistoricalStats() {
    if (this.throughputMode === 'live') {
      console.log('Live mode - using SSE data');
      return; // Live data comes from SSE
    }

    console.log('Loading historical stats for mode:', this.throughputMode);
    try {
      const endpoint = this.throughputMode === 'hour' ? '/api/throughput/history/hours' : '/api/throughput/history/days';
      console.log('Fetching from endpoint:', endpoint);
      const response = await fetch(endpoint);
      if (!response.ok) throw new Error('Failed to load historical stats');

      const data: HistoricalStats = await response.json();
      console.log('Historical stats loaded:', data);
      // For now, just show the latest data point
      if (data.dataPoints.length > 0) {
        const latest = data.dataPoints[data.dataPoints.length - 1];
        // Convert to ThroughputData format for display
        this.currentThroughput = {
          timestamp: latest.timestamp,
          categories: {
            blobUpload: { current: latest.writeBytes / 60, average: latest.writeBytes / 60, totalBytes: latest.writeBytes },
            blobDownload: { current: latest.readBytes / 60, average: latest.readBytes / 60, totalBytes: latest.readBytes },
            manifestUpload: { current: 0, average: 0, totalBytes: 0 },
            manifestDownload: { current: 0, average: 0, totalBytes: 0 }
          },
          overall: {
            read: { current: latest.readBytes / 60, average: latest.readBytes / 60, totalBytes: latest.readBytes },
            write: { current: latest.writeBytes / 60, average: latest.writeBytes / 60, totalBytes: latest.writeBytes },
            total: { current: (latest.readBytes + latest.writeBytes) / 60, average: (latest.readBytes + latest.writeBytes) / 60, totalBytes: latest.readBytes + latest.writeBytes }
          }
        };
        this.updateThroughputDisplay();
      } else {
        console.log('No historical data points available');
      }
    } catch (error) {
      console.error('Failed to load historical throughput stats:', error);
    }
  }

  private showIdleState() {
    const container = document.getElementById('throughput-container');
    if (!container) return;

    container.innerHTML = `
      <div class="throughput-card throughput-idle">
        <div class="throughput-header">
          <h6>Throughput</h6>
          <div class="throughput-controls">
            <select id="throughput-mode" class="form-select form-select-sm">
              <option value="live" ${this.throughputMode === 'live' ? 'selected' : ''}>Live</option>
              <option value="hour" ${this.throughputMode === 'hour' ? 'selected' : ''}>Hour</option>
              <option value="day" ${this.throughputMode === 'day' ? 'selected' : ''}>Day</option>
            </select>
            <select id="throughput-view" class="form-select form-select-sm">
              <option value="overall" ${this.throughputView === 'overall' ? 'selected' : ''}>Overall</option>
              <option value="category" ${this.throughputView === 'category' ? 'selected' : ''}>Category</option>
            </select>
          </div>
        </div>
        <div class="throughput-content">
          <div class="throughput-metric">
            <span class="throughput-label">● Read:</span>
            <span class="throughput-value">0.0 B/s</span>
          </div>
          <div class="throughput-metric">
            <span class="throughput-label">● Write:</span>
            <span class="throughput-value">0.0 B/s</span>
          </div>
          <div class="throughput-divider"></div>
          <div class="throughput-metric throughput-total">
            <span class="throughput-label">Total:</span>
            <span class="throughput-value">0.0 B/s</span>
            <small class="throughput-average">(idle)</small>
          </div>
        </div>
      </div>
    `;

    // Add event listeners for the controls
    this.setupThroughputControls();
  }

  private setupThroughputControls() {
    // Remove existing event listeners first
    const modeSelect = document.getElementById('throughput-mode') as HTMLSelectElement;
    const viewSelect = document.getElementById('throughput-view') as HTMLSelectElement;

    if (modeSelect) {
      // Clone the element to remove all event listeners
      const newModeSelect = modeSelect.cloneNode(true) as HTMLSelectElement;
      modeSelect.parentNode?.replaceChild(newModeSelect, modeSelect);

      newModeSelect.addEventListener('change', (e) => {
        const target = e.target as HTMLSelectElement;
        this.throughputMode = target.value as 'live' | 'hour' | 'day';
        console.log('Throughput mode changed to:', this.throughputMode);
        this.loadHistoricalStats();
      });
    }

    if (viewSelect) {
      // Clone the element to remove all event listeners
      const newViewSelect = viewSelect.cloneNode(true) as HTMLSelectElement;
      viewSelect.parentNode?.replaceChild(newViewSelect, viewSelect);

      newViewSelect.addEventListener('change', (e) => {
        const target = e.target as HTMLSelectElement;
        this.throughputView = target.value as 'overall' | 'category';
        console.log('Throughput view changed to:', this.throughputView);
        this.updateThroughputDisplay();
      });
    }
  }

  private addLogEntry(logEntry: LogEntry) {
    this.logs.unshift(logEntry);

    // Keep only the most recent logs
    if (this.logs.length > this.maxLogs) {
      this.logs = this.logs.slice(0, this.maxLogs);
    }

    // Throttle log display updates to prevent excessive DOM manipulation
    if (this.logDisplayUpdateThrottle) {
      clearTimeout(this.logDisplayUpdateThrottle);
    }

    this.logDisplayUpdateThrottle = window.setTimeout(() => {
      this.updateLogDisplay();
      this.logDisplayUpdateThrottle = null;
    }, 100); // Update display every 100ms max
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
    const liveText = indicator?.querySelector('.live-text');
    if (indicator && liveText) {
      if (live) {
        indicator.className = 'live-indicator live-active';
        liveText.textContent = 'LIVE';
      } else {
        indicator.className = 'live-indicator live-inactive';
        liveText.textContent = 'OFFLINE';
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

  private startDashboardAutoRefresh() {
    this.stopDashboardAutoRefresh(); // Clear any existing interval
    this.dashboardRefreshInterval = window.setInterval(() => {
      this.loadDashboard();
    }, this.dashboardRefreshIntervalMs);
    console.log(`Started dashboard auto-refresh every ${this.dashboardRefreshIntervalMs}ms`);
  }

  private stopDashboardAutoRefresh() {
    if (this.dashboardRefreshInterval) {
      clearInterval(this.dashboardRefreshInterval);
      this.dashboardRefreshInterval = null;
      console.log('Stopped dashboard auto-refresh');
    }
  }

  private updateLastRefreshTime() {
    const refreshElement = document.getElementById('last-refresh-time');
    if (refreshElement) {
      const now = new Date();
      refreshElement.textContent = now.toLocaleTimeString();
    }
  }

  private async setLogLevel(level: string) {
    try {
      const response = await fetch('/api/web/log-level', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer web-token-${this.getAuthToken()}`
        },
        body: JSON.stringify({ level })
      });

      if (response.ok) {
        this.currentLogLevel = level;
        this.showAlert(`Log level set to ${level}`, 'success');
      } else {
        const error = await response.json();
        this.showAlert(`Failed to set log level: ${error.error}`, 'danger');
      }
    } catch (error) {
      this.showAlert(`Failed to set log level: ${error}`, 'danger');
    }
  }

  private getAuthToken(): string {
    // Simple token retrieval - in production, use proper session management
    return 'admin'; // Default token
  }
}

// Global reference to the interface instance
let registryInterface: RegistryWebInterface;

// Initialize the application when DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
  registryInterface = new RegistryWebInterface('app');

  // Attach global functions to window object for HTML onclick handlers
  (window as any).deleteRepository = (repositoryName: string) => {
    if (registryInterface) {
      registryInterface.deleteRepository(repositoryName);
    }
  };

  (window as any).changePage = (page: number) => {
    if (registryInterface) {
      registryInterface.changePage(page);
    }
  };
});