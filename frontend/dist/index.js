var r=class{constructor(e){this.isAuthenticated=!1;this.container=document.getElementById(e),this.initializeApp()}async initializeApp(){await this.checkWebAuthRequired()?this.showLoginForm():this.showMainInterface()}async checkWebAuthRequired(){try{return(await fetch("/api/web/status")).status===401}catch{return!1}}showLoginForm(){this.container.innerHTML=`
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
    `,document.getElementById("login-form")?.addEventListener("submit",e=>{e.preventDefault(),this.handleLogin()})}async handleLogin(){let e=document.getElementById("username").value,s=document.getElementById("password").value,a=document.getElementById("login-error");try{let t=await fetch("/api/web/login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({username:e,password:s})});if(t.ok)this.isAuthenticated=!0,this.showMainInterface();else{let i=await t.json();a.textContent=i.message||"Login failed",a.style.display="block"}}catch{a.textContent="Network error during login",a.style.display="block"}}showMainInterface(){this.container.innerHTML=`
      <nav class="navbar navbar-expand-lg navbar-dark bg-dark">
        <div class="container">
          <a class="navbar-brand" href="#">NSCR Registry</a>
          <div class="navbar-nav ms-auto">
            <span class="navbar-text me-3">Registry Status</span>
            ${this.isAuthenticated?'<a class="nav-link" href="#" id="logout-btn">Logout</a>':""}
          </div>
        </div>
      </nav>
      
      <div class="container mt-4">
        <div id="dashboard-container"></div>
        <div id="repositories-container" class="mt-4"></div>
      </div>
    `,this.loadDashboard(),this.loadRepositories(),this.isAuthenticated&&document.getElementById("logout-btn")?.addEventListener("click",()=>{this.logout()})}async loadDashboard(){let e=document.getElementById("dashboard-container");if(e)try{let s=await fetch("/api/web/status");if(!s.ok)throw new Error("Failed to load status");let a=await s.json();this.renderDashboard(a)}catch(s){e.innerHTML=`
        <div class="alert alert-danger">
          <h5>Error Loading Dashboard</h5>
          <p>Failed to load registry status: ${s}</p>
        </div>
      `}}renderDashboard(e){let s=document.getElementById("dashboard-container");s&&(s.innerHTML=`
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
              <h2 class="card-text">${e.repositories}</h2>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card text-white bg-success">
            <div class="card-body">
              <h5 class="card-title">Total Blobs</h5>
              <h2 class="card-text">${e.totalBlobs}</h2>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card text-white bg-info">
          <div class="card-body">
              <h5 class="card-title">Total Manifests</h5>
              <h2 class="card-text">${e.totalManifests}</h2>
            </div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="card text-white bg-warning">
            <div class="card-body">
              <h5 class="card-title">Unreferenced Blobs</h5>
              <h2 class="card-text">${e.unreferencedBlobs}</h2>
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
              <p><strong>Estimated Space to Free:</strong> ${this.formatBytes(e.estimatedSpaceToFree)}</p>
              ${e.lastGcRun?`<p><strong>Last GC Run:</strong> ${new Date(e.lastGcRun).toLocaleString()}</p>`:""}
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
    `,document.getElementById("gc-btn")?.addEventListener("click",()=>{this.runGarbageCollection()}))}async runGarbageCollection(){let e=document.getElementById("gc-btn"),s=e.innerHTML;try{e.disabled=!0,e.innerHTML='<span class="spinner-border spinner-border-sm"></span> Running...';let a=await fetch("/api/garbage-collect",{method:"POST"});if(!a.ok)throw new Error("Garbage collection failed");let t=await a.json();this.showAlert(`
        <strong>Garbage Collection Completed!</strong><br>
        Blobs removed: ${t.blobsRemoved}<br>
        Manifests removed: ${t.manifestsRemoved}<br>
        Space freed: ${this.formatBytes(t.spaceFreed)}
      `,"success"),this.loadDashboard()}catch(a){this.showAlert(`Failed to run garbage collection: ${a}`,"danger")}finally{e.disabled=!1,e.innerHTML=s}}async loadRepositories(){let e=document.getElementById("repositories-container");if(e)try{let s=await fetch("/v2/_catalog");if(!s.ok)throw new Error("Failed to load repositories");let a=await s.json();this.renderRepositories(a.repositories)}catch(s){e.innerHTML=`
        <div class="alert alert-warning">
          <h5>Repositories</h5>
          <p>Failed to load repositories: ${s}</p>
        </div>
      `}}async renderRepositories(e){let s=document.getElementById("repositories-container");if(!s)return;if(e.length===0){s.innerHTML=`
        <div class="card">
          <div class="card-header">
            <h5 class="mb-0">Repositories</h5>
          </div>
          <div class="card-body">
            <p class="text-muted">No repositories found in the registry.</p>
          </div>
        </div>
      `;return}let a=await Promise.all(e.map(async t=>{try{let i=await fetch(`/v2/${t}/tags/list`);if(i.ok){let n=await i.json();return{name:t,tags:n.tags||[]}}}catch(i){console.warn(`Failed to load tags for ${t}:`,i)}return{name:t,tags:[]}}));s.innerHTML=`
      <div class="card">
        <div class="card-header">
          <h5 class="mb-0">Repositories (${e.length})</h5>
        </div>
        <div class="card-body">
          <div class="row">
            ${a.map(t=>`
              <div class="col-md-6 mb-3">
                <div class="card">
                  <div class="card-body">
                    <h6 class="card-title">${t.name}</h6>
                    <p class="card-text">
                      <span class="badge bg-secondary">${t.tags.length} tags</span>
                    </p>
                    ${t.tags.length>0?`
                      <div class="mt-2">
                        <small class="text-muted">Latest tags:</small><br>
                        ${t.tags.slice(0,3).map(i=>`<span class="badge bg-light text-dark me-1">${i}</span>`).join("")}
                        ${t.tags.length>3?`<span class="text-muted">+${t.tags.length-3} more</span>`:""}
                      </div>
                    `:""}
                  </div>
                </div>
              </div>
            `).join("")}
          </div>
        </div>
      </div>
    `}formatBytes(e){if(e===0)return"0 Bytes";let s=1024,a=["Bytes","KB","MB","GB","TB"],t=Math.floor(Math.log(e)/Math.log(s));return parseFloat((e/Math.pow(s,t)).toFixed(2))+" "+a[t]}showAlert(e,s){let a=document.createElement("div");a.className=`alert alert-${s} alert-dismissible fade show`,a.innerHTML=`
      ${e}
      <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
    `;let t=document.getElementById("dashboard-container");t&&(t.insertBefore(a,t.firstChild),setTimeout(()=>{a.parentNode&&a.remove()},5e3))}logout(){this.isAuthenticated=!1,this.initializeApp()}};document.addEventListener("DOMContentLoaded",()=>{new r("app")});
//# sourceMappingURL=index.js.map
