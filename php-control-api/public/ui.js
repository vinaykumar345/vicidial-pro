const byId = (id) => document.getElementById(id);

const state = {
  agent: {
    username: "",
    password: "",
    fullName: "",
    leads: [],
    activeLeadId: ""
  },
  admin: {
    username: "",
    password: "",
    fullName: ""
  }
};

function nowStamp() {
  return new Date().toLocaleString();
}

function appendAudit(el, line) {
  if (!el) return;
  const next = `[${nowStamp()}] ${line}`;
  el.textContent = el.textContent ? `${next}\n${el.textContent}` : next;
}

function setStatus(el, message, level = "warn") {
  if (!el) return;
  el.classList.remove("ok", "warn", "err");
  el.classList.add(level);
  el.textContent = message;
}

function setTextIfExists(id, value) {
  const el = byId(id);
  if (el) {
    el.textContent = value;
  }
}

function buildMockLeads(agentUser) {
  const owner = agentUser || "agent";
  return [
    {
      id: "LD-1001",
      name: "Rakesh Sharma",
      phone: "+91 98859 76256",
      city: "Hyderabad",
      source: "Website Form",
      score: "Hot",
      timeline: [
        { when: "Today 10:02", note: "Requested EMI details for premium plan." },
        { when: "Yesterday 16:20", note: "Connected and asked for callback post salary credit." }
      ]
    },
    {
      id: "LD-1002",
      name: "Swathi Reddy",
      phone: "+91 98490 22114",
      city: "Warangal",
      source: "Referral",
      score: "Warm",
      timeline: [
        { when: "Today 09:10", note: "Could not talk, preferred evening slot." },
        { when: "2 days ago", note: "Shared brochure via WhatsApp." }
      ]
    },
    {
      id: "LD-1003",
      name: "Arjun Nair",
      phone: "+91 99887 44210",
      city: "Bengaluru",
      source: "Campaign Import",
      score: "Cold",
      timeline: [
        { when: "Today 11:34", note: "Number answered by colleague, not decision maker." },
        { when: "3 days ago", note: "No answer after two attempts." }
      ]
    }
  ].map((lead) => ({ ...lead, owner, disposition: "Pending" }));
}

function renderLeadWorkspace() {
  const listEl = byId("leadList");
  const timelineEl = byId("leadTimeline");
  const nameEl = byId("leadName");
  const phoneEl = byId("leadPhone");
  const metaEl = byId("leadMeta");

  if (!listEl || !timelineEl || !nameEl || !phoneEl || !metaEl) {
    return;
  }

  listEl.innerHTML = "";
  state.agent.leads.forEach((lead) => {
    const item = document.createElement("button");
    item.type = "button";
    item.className = `lead-item${lead.id === state.agent.activeLeadId ? " active" : ""}`;
    item.innerHTML = `
      <div class="lead-item-name">${lead.name}</div>
      <div class="lead-item-sub">${lead.id} • ${lead.score} • ${lead.disposition}</div>
    `;
    item.addEventListener("click", () => {
      state.agent.activeLeadId = lead.id;
      renderLeadWorkspace();
    });
    listEl.appendChild(item);
  });

  const selected = state.agent.leads.find((lead) => lead.id === state.agent.activeLeadId);
  if (!selected) {
    timelineEl.innerHTML = "";
    nameEl.textContent = "Select a lead";
    phoneEl.textContent = "-";
    metaEl.textContent = "No lead selected";
    return;
  }

  nameEl.textContent = `${selected.name} (${selected.id})`;
  phoneEl.textContent = selected.phone;
  metaEl.textContent = `${selected.city} • ${selected.source} • Owner ${selected.owner} • ${selected.disposition}`;

  timelineEl.innerHTML = "";
  selected.timeline.forEach((event) => {
    const row = document.createElement("div");
    row.className = "timeline-item";
    row.innerHTML = `
      <div class="timeline-time">${event.when}</div>
      <div class="timeline-note">${event.note}</div>
    `;
    timelineEl.appendChild(row);
  });
}

function applyLeadDisposition(nextDisposition) {
  const selected = state.agent.leads.find((lead) => lead.id === state.agent.activeLeadId);
  const hintEl = byId("leadDispositionHint");
  if (!selected) {
    if (hintEl) {
      hintEl.textContent = "Select a lead before applying disposition.";
    }
    return;
  }

  selected.disposition = nextDisposition;
  selected.timeline.unshift({ when: "Now", note: `Disposition updated to ${nextDisposition}.` });
  if (hintEl) {
    hintEl.textContent = `Saved disposition: ${nextDisposition} for ${selected.name}`;
  }
  setTextIfExists("kpiLastAction", `Disposition: ${nextDisposition}`);
  renderLeadWorkspace();
}

function initLeadWorkspace() {
  if (!byId("leadBoardCard")) {
    return;
  }

  if (!Array.isArray(state.agent.leads) || state.agent.leads.length === 0) {
    state.agent.leads = buildMockLeads(state.agent.username);
    state.agent.activeLeadId = state.agent.leads[0]?.id || "";
  }

  Array.from(document.querySelectorAll(".disp-btn")).forEach((btn) => {
    if (btn.dataset.bound === "1") {
      return;
    }
    btn.dataset.bound = "1";
    btn.addEventListener("click", () => {
      const disp = btn.getAttribute("data-disp") || "Pending";
      applyLeadDisposition(disp);
    });
  });

  renderLeadWorkspace();
}

async function apiCall(path, payload, apiKey) {
  const res = await fetch(path, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Api-Key": apiKey
    },
    body: JSON.stringify(payload)
  });
  const text = await res.text();
  try {
    return { status: res.status, data: JSON.parse(text) };
  } catch {
    return { status: res.status, data: { ok: false, error: text } };
  }
}

async function healthCheck(apiKey, statusEl, auditEl) {
  const res = await fetch("/health", {
    headers: { "X-Api-Key": apiKey }
  });
  const txt = await res.text();
  appendAudit(auditEl, `health ${res.status}: ${txt}`);
  if (res.ok && txt.includes("\"ok\":true")) {
    setStatus(statusEl, "Control API healthy", "ok");
  } else {
    setStatus(statusEl, `Health failed (${res.status})`, "err");
  }
}

async function loadCampaigns() {
  const user = state.agent.username || byId("agentUser").value.trim();
  const pass = state.agent.password || byId("agentPass").value.trim();
  const apiKey = byId("apiKey").value.trim();
  const statusEl = byId("agentStatus");
  const auditEl = byId("agentAudit");
  const campaignSel = byId("campaign");

  if (!user || !pass) {
    setStatus(statusEl, "Enter user and password first", "warn");
    return;
  }

  setStatus(statusEl, "Loading assigned campaigns...", "warn");
  const { status, data } = await apiCall("/api/agent-campaigns", {
    agentUser: user,
    agentPassword: pass
  }, apiKey);

  appendAudit(auditEl, `campaigns ${status}: ${JSON.stringify(data)}`);

  campaignSel.innerHTML = "";
  if (data.ok && Array.isArray(data.campaigns) && data.campaigns.length > 0) {
    data.campaigns.forEach((c) => {
      const id = c.campaignId || c.id || "";
      const name = c.campaignName || c.name || "Campaign";
      const opt = document.createElement("option");
      opt.value = id;
      opt.textContent = `${id} - ${name}`;
      campaignSel.appendChild(opt);
    });
    setStatus(statusEl, "Campaigns loaded", "ok");
    setTextIfExists("kpiCampaignCount", String(data.campaigns.length));
    setTextIfExists("kpiLastAction", "Campaign sync");
  } else {
    setStatus(statusEl, data.message || "No campaigns found", "warn");
  }
}

async function agentLogin() {
  const user = state.agent.username || byId("agentUser").value.trim();
  const pass = state.agent.password || byId("agentPass").value.trim();
  const apiKey = byId("apiKey").value.trim();
  const campaign = byId("campaign").value;
  const statusEl = byId("agentStatus");
  const auditEl = byId("agentAudit");

  if (!user || !pass || !campaign) {
    setStatus(statusEl, "User, password and campaign are required", "warn");
    return;
  }

  setStatus(statusEl, "Logging in...", "warn");
  const { status, data } = await apiCall("/api/agent-login", {
    agentUser: user,
    agentPassword: pass,
    campaignId: campaign
  }, apiKey);

  appendAudit(auditEl, `login ${status}: ${JSON.stringify(data)}`);
  if (data.ok) {
    setStatus(statusEl, data.message || "Agent login validated", "ok");
    setTextIfExists("kpiAgentState", "Ready");
    setTextIfExists("kpiLastAction", "Login validated");
  } else {
    setStatus(statusEl, data.message || data.error || "Login failed", "err");
  }
}

async function externalDial() {
  const user = state.agent.username || byId("agentUser").value.trim();
  const number = byId("dialNumber").value.trim();
  const apiKey = byId("apiKey").value.trim();
  const statusEl = byId("agentStatus");
  const auditEl = byId("agentAudit");

  if (!user || !number) {
    setStatus(statusEl, "Agent user and dial number are required", "warn");
    return;
  }

  setStatus(statusEl, "Triggering external dial...", "warn");
  const { status, data } = await apiCall("/api/external-dial", {
    agentUser: user,
    phoneNumber: number
  }, apiKey);

  const body = data?.upstream?.body || data?.error || "No response body";
  appendAudit(auditEl, `external-dial ${status}: ${body}`);
  if (body.includes("SUCCESS")) {
    setStatus(statusEl, body, "ok");
    setTextIfExists("kpiLastAction", "External dial");
  } else {
    setStatus(statusEl, body, "err");
  }
}

async function supervisorAction(stage) {
  const apiKey = byId("adminApiKey").value.trim();
  const phoneLogin = byId("phoneLogin").value.trim();
  const sessionId = byId("sessionId").value.trim();
  const serverIp = byId("serverIp").value.trim();
  const statusEl = byId("adminStatus");
  const auditEl = byId("adminAudit");

  if (!phoneLogin || !sessionId || !serverIp) {
    setStatus(statusEl, "Phone login, session ID and server IP are required", "warn");
    return;
  }

  const path = stage === "MONITOR" ? "/api/monitor" : "/api/barge";
  setStatus(statusEl, `Launching ${stage.toLowerCase()}...`, "warn");
  const { status, data } = await apiCall(path, {
    phoneLogin,
    sessionId,
    serverIp
  }, apiKey);

  const body = data?.upstream?.body || data?.error || "No response body";
  appendAudit(auditEl, `${stage.toLowerCase()} ${status}: ${body}`);
  if (body.includes("SUCCESS")) {
    setStatus(statusEl, body, "ok");
    setTextIfExists("kpiAdminAction", stage === "MONITOR" ? "Monitor launched" : "Barge launched");
  } else {
    setStatus(statusEl, body, "err");
  }
}

async function gateAgentPanel() {
  const apiKey = byId("apiKey").value.trim();
  const username = byId("gateAgentUser").value.trim();
  const password = byId("gateAgentPass").value.trim();
  const statusEl = byId("agentStatus");
  const auditEl = byId("agentAudit");

  if (!username || !password) {
    setStatus(statusEl, "Enter agent username and password", "warn");
    return;
  }

  setStatus(statusEl, "Authenticating agent...", "warn");
  const { status, data } = await apiCall("/api/ui-auth", {
    username,
    password,
    panel: "agent"
  }, apiKey);

  appendAudit(auditEl, `ui-auth(agent) ${status}: ${JSON.stringify(data)}`);
  if (!data.ok) {
    setStatus(statusEl, data.message || data.error || "Authentication failed", "err");
    return;
  }

  state.agent.username = username;
  state.agent.password = password;
  state.agent.fullName = data.fullName || "";

  byId("agentGate").classList.add("hidden");
  byId("agentApp").classList.remove("hidden");
  byId("agentIdentity").value = `${state.agent.fullName || username} (${username})`;

  byId("agentUser").value = username;
  byId("agentPass").value = password;

  const campaignSel = byId("campaign");
  campaignSel.innerHTML = "";
  (data.campaigns || []).forEach((c) => {
    const opt = document.createElement("option");
    opt.value = c.id;
    opt.textContent = `${c.id} - ${c.name}`;
    campaignSel.appendChild(opt);
  });

  setStatus(statusEl, "Agent authenticated. Console unlocked.", "ok");
  setTextIfExists("kpiAgentState", "Unlocked");
  setTextIfExists("kpiCampaignCount", String((data.campaigns || []).length));
  setTextIfExists("kpiLastAction", "Console unlocked");
  initLeadWorkspace();
}

async function gateAdminPanel() {
  const apiKey = byId("adminApiKey").value.trim();
  const username = byId("gateAdminUser").value.trim();
  const password = byId("gateAdminPass").value.trim();
  const statusEl = byId("adminStatus");
  const auditEl = byId("adminAudit");

  if (!username || !password) {
    setStatus(statusEl, "Enter admin username and password", "warn");
    return;
  }

  setStatus(statusEl, "Authenticating admin...", "warn");
  const { status, data } = await apiCall("/api/ui-auth", {
    username,
    password,
    panel: "admin"
  }, apiKey);

  appendAudit(auditEl, `ui-auth(admin) ${status}: ${JSON.stringify(data)}`);
  if (!data.ok) {
    setStatus(statusEl, data.message || data.error || "Authentication failed", "err");
    return;
  }

  state.admin.username = username;
  state.admin.password = password;
  state.admin.fullName = data.fullName || "";

  byId("adminGate").classList.add("hidden");
  byId("adminApp").classList.remove("hidden");
  byId("adminIdentity").value = `${state.admin.fullName || username} (${username})`;

  setStatus(statusEl, "Admin authenticated. Panel unlocked.", "ok");
  setTextIfExists("kpiAdminState", "Unlocked");
  setTextIfExists("kpiAdminAction", "Panel unlocked");
}

function applySessionRow(session) {
  byId("phoneLogin").value = session.agentUser || "";
  byId("sessionId").value = session.sessionId || "";
  byId("serverIp").value = session.serverIp || "";
}

async function discoverSessions() {
  const apiKey = byId("adminApiKey").value.trim();
  const statusEl = byId("adminStatus");
  const auditEl = byId("adminAudit");
  const filterAgent = byId("sessionAgentFilter").value.trim();

  if (!state.admin.username || !state.admin.password) {
    setStatus(statusEl, "Authenticate admin first", "warn");
    return;
  }

  setStatus(statusEl, "Discovering live sessions...", "warn");
  const { status, data } = await apiCall("/api/live-sessions", {
    adminUser: state.admin.username,
    adminPassword: state.admin.password,
    agentUser: filterAgent,
    limit: 30
  }, apiKey);

  appendAudit(auditEl, `live-sessions ${status}: ${JSON.stringify(data)}`);
  const tbody = byId("sessionRows");
  tbody.innerHTML = "";

  if (!data.ok || !Array.isArray(data.sessions) || data.sessions.length === 0) {
    setStatus(statusEl, data.message || "No live sessions found", "warn");
    return;
  }

  setTextIfExists("kpiSessionCount", String(data.sessions.length));
  setTextIfExists("kpiAdminAction", "Sessions refreshed");

  data.sessions.forEach((s, idx) => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${s.agentUser || ""}</td>
      <td>${s.fullName || ""}</td>
      <td>${s.campaignId || ""}</td>
      <td>${s.status || ""}</td>
      <td>${s.sessionId || ""}</td>
      <td>${s.serverIp || ""}</td>
      <td>${s.lastStateChange || ""}</td>
      <td class="table-actions"><button type="button" class="secondary" data-idx="${idx}">Use</button></td>
    `;
    tbody.appendChild(tr);
  });

  Array.from(tbody.querySelectorAll("button[data-idx]")).forEach((btn) => {
    btn.addEventListener("click", () => {
      const i = Number(btn.getAttribute("data-idx"));
      const picked = data.sessions[i];
      if (picked) {
        applySessionRow(picked);
        setStatus(statusEl, `Selected session ${picked.sessionId} for ${picked.agentUser}`, "ok");
      }
    });
  });

  applySessionRow(data.sessions[0]);
  setStatus(statusEl, `Loaded ${data.sessions.length} live session(s)`, "ok");
}

async function hydrateLandingCards() {
  const apiHealth = byId("landingApiHealth");
  const refresh = byId("landingRefresh");
  const goal = byId("landingGoal");
  const tools = byId("landingSupervisorTools");

  if (!apiHealth || !refresh) {
    return;
  }

  if (goal) {
    goal.textContent = "Fast Call Operations";
  }
  if (tools) {
    tools.textContent = "Monitor + Barge";
  }

  try {
    const res = await fetch("/health", {
      headers: { "X-Api-Key": "change-me" }
    });
    apiHealth.textContent = res.ok ? "Healthy" : `Error (${res.status})`;
  } catch {
    apiHealth.textContent = "Unavailable";
  }

  refresh.textContent = new Date().toLocaleTimeString();
}

window.addEventListener("DOMContentLoaded", () => {
  const isLanding = Boolean(byId("landingPage"));
  const isAgent = Boolean(byId("agentPage"));
  const isAdmin = Boolean(byId("adminPage"));

  if (isLanding) {
    hydrateLandingCards();
  }

  if (isAgent) {
    initLeadWorkspace();
    byId("unlockAgentBtn").addEventListener("click", gateAgentPanel);
    byId("loadCampaignsBtn").addEventListener("click", loadCampaigns);
    byId("loginBtn").addEventListener("click", agentLogin);
    byId("dialBtn").addEventListener("click", externalDial);
    byId("healthBtn").addEventListener("click", async () => {
      await healthCheck(byId("apiKey").value.trim(), byId("agentStatus"), byId("agentAudit"));
    });
  }

  if (isAdmin) {
    byId("unlockAdminBtn").addEventListener("click", gateAdminPanel);
    byId("discoverSessionsBtn").addEventListener("click", discoverSessions);
    byId("monitorBtn").addEventListener("click", () => supervisorAction("MONITOR"));
    byId("bargeBtn").addEventListener("click", () => supervisorAction("BARGE"));
    byId("adminHealthBtn").addEventListener("click", async () => {
      await healthCheck(byId("adminApiKey").value.trim(), byId("adminStatus"), byId("adminAudit"));
    });
  }
});
