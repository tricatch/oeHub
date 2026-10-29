// Share-scope picker (share-scope-select.pebble). Page code keeps working with the hidden
// #shareScopeSelect: picking a menu entry writes its value and fires 'change', and page code
// calls syncShareScopeMenu() after it sets the select's value or disabled state itself.
function syncShareScopeMenu() {
  const select = document.getElementById('shareScopeSelect');
  const btn = document.getElementById('shareScopeBtn');
  if (!select || !btn) return;
  document.querySelectorAll('#shareScopeMenu [data-value]').forEach(item => {
    const on = item.dataset.value === select.value;
    item.classList.toggle('active', on);
    if (on) {
      document.getElementById('shareScopeIcon').className = item.querySelector('.oe-icon').className;
      document.getElementById('shareScopeLabel').textContent = item.textContent.trim();
    }
  });
  btn.disabled = select.disabled;
}

document.addEventListener('click', e => {
  const item = e.target.closest('#shareScopeMenu [data-value]');
  if (!item) return;
  const select = document.getElementById('shareScopeSelect');
  if (item.dataset.value === select.value) return;
  select.value = item.dataset.value;
  syncShareScopeMenu();
  select.dispatchEvent(new Event('change'));
});
