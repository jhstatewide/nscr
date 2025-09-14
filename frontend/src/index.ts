// Bootstrap is loaded via CDN in the HTML template

interface RegistryStats {
  repositories: number;
  totalBlobs: number;
  totalManifests: number;
  unreferencedBlobs: number;
  estimatedSpaceToFree: number;
  lastGcRun?: string;
}

interface GarbageCollectionResult {
  blobsRemoved: number;
  spaceFreed: number;
  manifestsRemoved: number;
}

class RegistryWebInterface {
  private container: HTMLElement;
  private isAuthenticated = false;

  constructor(containerId: string) {
    this.container = document.getElementById(containerId)!;
    this.initializeApp();
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
      </div>
    `;

    this.loadDashboard();
    this.loadRepositories();

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
    `;

    // Setup garbage collection button
    document.getElementById('gc-btn')?.addEventListener('click', () => {
      this.runGarbageCollection();
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
    this.initializeApp();
  }
}

// Initialize the application when DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
  new RegistryWebInterface('app');
});
