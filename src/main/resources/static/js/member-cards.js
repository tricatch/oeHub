// The invite-code card and the pending-approval card, shared by the workspace admin's Users page
// (/wsa/users) and the regular members' page (/oehub/members). Both pages render the same markup
// (_invites-card.pebble / _pending-card.pebble) and pass in the API they talk to; the differences
// - which invites are listed, whether "reject" exists - are decided by the API and the config, not
// by this file. Needs util.js (escHtml, showToast, copyToClipboard), crypto.js and session-keys.js.
var OE_MEMBER_CARDS = (function () {

  // ── Invite codes ─────────────────────────────────────────────
  // cfg: { listUrl, createUrl }. Returns { reload, setTeams } (null when the card isn't on the page).
  function initInvites(cfg) {
    var tbody = document.getElementById('invitesTbody');
    if (!tbody) return null;

    function load() {
      fetch(cfg.listUrl)
        .then(function (r) { return r.json(); })
        .then(render)
        .catch(function () {});
    }

    function render(invites) {
      if (!invites || invites.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4" class="text-muted text-center py-3">' + escHtml(MSG['users.invite.empty']) + '</td></tr>';
        return;
      }
      tbody.innerHTML = invites.map(function (i) {
        return '<tr data-invite-code="' + escHtml(i.inviteCode) + '">' +
          '<td class="small text-muted">' + escHtml(i.teamName || '-') + '</td>' +
          '<td class="setup-mono">' + escHtml(i.inviteCode) + '</td>' +
          '<td class="small text-muted">' + escHtml(i.expiresAt) + '</td>' +
          '<td class="text-end"><button class="oe-tbl-btn oe-tbl-btn-action btn-copy-invite" title="' + escHtml(MSG['ui.copy.clipboard']) + '">' +
          '<span class="oe-icon oe-icon-copy" style="width:13px;height:13px"></span></button></td></tr>';
      }).join('');
      tbody.querySelectorAll('.btn-copy-invite').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var code = btn.closest('tr').dataset.inviteCode;
          var url = location.origin + '/register?invite=' + encodeURIComponent(code);
          copyToClipboard(url).then(function () { showToast(MSG['toast.copied']); });
        });
      });
    }

    // The team dropdown next to the create button: hidden while the workspace has no teams.
    function setTeams(teams) {
      var select = document.getElementById('inviteTeamSelect');
      if (!select) return;
      if (!teams || teams.length === 0) {
        select.hidden = true;
        select.innerHTML = '';
        return;
      }
      select.hidden = false;
      select.innerHTML = '<option value="">' + escHtml(MSG['teams.select.none']) + '</option>' +
        teams.map(function (t) { return '<option value="' + t.teamNo + '">' + escHtml(t.teamName) + '</option>'; }).join('');
    }

    var btnCreate = document.getElementById('btnCreateInvite');
    if (btnCreate) {
      btnCreate.addEventListener('click', function () {
        btnCreate.disabled = true;
        var teamSelect = document.getElementById('inviteTeamSelect');
        var teamNo = teamSelect && teamSelect.value ? Number(teamSelect.value) : null;
        fetch(cfg.createUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ teamNo: teamNo })
        })
          .then(function (r) {
            btnCreate.disabled = false;
            if (r.ok) { load(); return; }
            showToast(MSG['toast.invite.create.failed']);
          }).catch(function () {
            btnCreate.disabled = false;
            showToast(MSG['toast.invite.create.failed']);
          });
      });
    }

    load();
    return { reload: load, setTeams: setTeams };
  }

  // ── Pending sign-ups ─────────────────────────────────────────
  // cfg: { listUrl, approveUrl(userNo), rejectUrl(userNo) | null, onApproved() | null }.
  // rejectUrl null means the reject button is not offered. Returns { reload }.
  function initPending(cfg) {
    var tbody = document.getElementById('pendingTbody');
    if (!tbody) return null;

    function load() {
      fetch(cfg.listUrl)
        .then(function (r) { return r.json(); })
        .then(render)
        .catch(function () {});
    }

    // The card is always shown; with nobody waiting it holds a single "no pending sign-ups" row.
    function showEmpty() {
      tbody.innerHTML =
        '<tr><td colspan="3" class="text-muted text-center py-3">' + escHtml(MSG['users.pending.empty']) + '</td></tr>';
    }

    function showEmptyIfNone() {
      if (!tbody.querySelector('tr[data-user-no]')) showEmpty();
    }

    function render(pending) {
      if (!pending || pending.length === 0) {
        showEmpty();
        return;
      }
      tbody.innerHTML = pending.map(function (u) {
        return '<tr data-user-no="' + u.userNo + '" data-public-key="' + escHtml(u.publicKey || '') + '">' +
          '<td>' + escHtml(u.userId) + '</td>' +
          '<td class="small text-muted">' + escHtml(u.createAt) + '</td>' +
          '<td class="text-end">' +
          '<button class="oe-tbl-btn oe-tbl-btn-action btn-approve-pending" title="' + escHtml(MSG['users.btn.approve']) + '">' +
          '<span class="oe-icon oe-icon-approve" style="width:15px;height:15px"></span></button>' +
          (cfg.rejectUrl
            ? ' <button class="oe-tbl-btn oe-tbl-btn-delete btn-reject-pending ms-1" title="' + escHtml(MSG['users.btn.reject']) + '">' +
              '<span class="oe-icon oe-icon-delete" style="width:13px;height:13px"></span></button>'
            : '') +
          '</td></tr>';
      }).join('');
      tbody.querySelectorAll('.btn-approve-pending').forEach(function (btn) {
        btn.addEventListener('click', onApprove);
      });
      tbody.querySelectorAll('.btn-reject-pending').forEach(function (btn) {
        btn.addEventListener('click', onReject);
      });
    }

    function onApprove() {
      var btn = this;
      var row = btn.closest('tr');
      var userNo = row.dataset.userNo;
      var publicKeyB64 = row.dataset.publicKey;
      if (!confirm(MSG['confirm.approve.user'])) return;
      btn.disabled = true;

      approveWithWorkspaceKeyWrap(userNo, publicKeyB64).catch(function (err) {
        console.error('Approve failed:', err);
        btn.disabled = false;
        showToast(MSG['toast.user.approve.failed']);
      });
    }

    async function approveWithWorkspaceKeyWrap(userNo, publicKeyB64) {
      var row = tbody.querySelector('tr[data-user-no="' + userNo + '"]');
      var btn = row.querySelector('.btn-approve-pending');

      var wrappedWsKey;
      if (!window.OE_WORKSPACE_MODE) {
        // self-hosted never had a real workspace key to wrap in the first place (dummy identity
        // crypto - e2eEncryption design doc §1), and neither the approver nor the pending member
        // has one cached.
        wrappedWsKey = 'self-hosted';
      } else {
        // The approver must have their own workspace key cached from login (e2eEncryption design
        // doc §3/§5) - if not (e.g. this account predates the crypto wiring, or the cache was
        // never populated), approval can't proceed: there would be nothing correct to wrap.
        var wsKey = await OE_SESSION_KEYS.loadWorkspaceKey();
        if (!wsKey || !publicKeyB64) {
          btn.disabled = false;
          showToast(MSG['toast.user.approve.failed']);
          return;
        }
        var publicKey = await OE_CRYPTO.importPublicKey(publicKeyB64);
        wrappedWsKey = await OE_CRYPTO.wrapWorkspaceKeyForUser(wsKey, publicKey);
      }

      var r = await fetch(cfg.approveUrl(userNo), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ wrappedWsKey: wrappedWsKey })
      });
      if (r.ok) {
        showToast(MSG['toast.user.approved']);
        row.remove();
        showEmptyIfNone();
        if (cfg.onApproved) cfg.onApproved();
      } else {
        btn.disabled = false;
        showToast(MSG['toast.user.approve.failed']);
      }
    }

    function onReject() {
      var btn = this;
      var row = btn.closest('tr');
      var userNo = row.dataset.userNo;
      if (!confirm(MSG['confirm.reject.user'])) return;
      btn.disabled = true;
      fetch(cfg.rejectUrl(userNo), { method: 'POST' })
        .then(function (r) {
          if (r.ok) {
            showToast(MSG['toast.user.rejected']);
            row.remove();
            showEmptyIfNone();
          } else {
            btn.disabled = false;
            showToast(MSG['toast.user.reject.failed']);
          }
        }).catch(function () {
          btn.disabled = false;
          showToast(MSG['toast.user.reject.failed']);
        });
    }

    load();
    return { reload: load };
  }

  return { initInvites: initInvites, initPending: initPending };
})();
