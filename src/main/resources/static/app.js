const $ = (selector) => document.querySelector(selector);
const state = { selectedSlot: null, checkedParameters: null, bookings: [] };
const money = (value) => '₹' + Number(value || 0).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const vehicleLabel = (value) => value === 'TWO_WHEELER' ? 'Two-wheeler' : 'Car';
const statusLabel = (value) => ({ BOOKED: 'Booked', CHECKED_IN: 'Checked in', CHECKED_OUT: 'Checked out', CANCELLED: 'Cancelled' }[value] || value);
let toastTimer;
function notify(message) {
  const toast = $('#toast'); toast.textContent = message; toast.classList.add('show');
  clearTimeout(toastTimer); toastTimer = setTimeout(() => toast.classList.remove('show'), 5000);
}
async function api(path, options = {}) {
  let response;
  try { response = await fetch('/api' + path, { headers: { 'Content-Type': 'application/json' }, ...options }); }
  catch (_) { throw new Error('Cannot connect to the server. Start the Spring Boot application and refresh.'); }
  let result = {};
  try { result = await response.json(); } catch (_) { /* Some server errors do not return JSON. */ }
  // the session has ended: back to the sign-in screen, unless this was a sign-in attempt being refused
  if (response.status === 401 && !path.startsWith('/auth/')) { showAuth(false); throw new Error('Please sign in again.'); }
  if (!response.ok) throw new Error(result.message || 'The request could not be completed.');
  return result;
}
function parameters() {
  return { startTime: $('#startTime').value, hours: Number($('#duration').value), vehicleType: $('#vehicleType').value };
}
function resetAvailability() { state.selectedSlot = null; state.checkedParameters = null; $('#availabilityResult').classList.add('hidden'); }
['#startTime', '#duration', '#vehicleType'].forEach(id => $(id).addEventListener('change', resetAvailability));
async function loadStats() {
  const stats = await api('/statistics');
  $('#total').textContent = stats.total || 0; $('#checkedIn').textContent = stats.checked_in || 0;
  $('#completed').textContent = stats.completed || 0; $('#charges').textContent = money(stats.completed_charges);
}
async function loadBookings() {
  const search = $('#searchInput').value.trim();
  const params = new URLSearchParams({ search, by: $('#searchBy').value });
  state.bookings = await api('/bookings?' + params);
  const rows = $('#bookingRows');
  if (!state.bookings.length) { rows.innerHTML = '<tr><td colspan="7" class="empty">No bookings found. Create one to get started.</td></tr>'; return; }
  rows.innerHTML = state.bookings.map(b => {
    const id = Number(b.id); const status = String(b.status);
    let actions = `<button class="action" data-action="receipt" data-id="${id}">Receipt</button>`;
    if (status === 'BOOKED') actions += `<button class="action" data-action="check-in" data-id="${id}">Check in</button><button class="action" data-action="cancel" data-id="${id}">Cancel</button>`;
    if (status === 'CHECKED_IN') actions += `<button class="action" data-action="check-out" data-id="${id}">Check out</button>`;
    if ((status === 'BOOKED' || status === 'CHECKED_IN') && Number(b.hours) < 5) actions += `<button class="action" data-action="extend" data-id="${id}">Extend</button>`;
    return `<tr><td><strong>#${id}</strong></td><td><strong>${escapeHtml(b.car_number)}</strong><small>${vehicleLabel(b.vehicle_type)}</small></td><td>Slot ${Number(b.slot)}</td><td>${escapeHtml(b.start_time)}<small>to ${escapeHtml(b.end_time)} · ${Number(b.hours)} hr</small></td><td>${money(b.price)}</td><td><span class="badge ${escapeHtml(status.toLowerCase())}">${escapeHtml(statusLabel(status))}</span></td><td><div class="actions">${actions}</div></td></tr>`;
  }).join('');
}
async function refreshAll() {
  try { await Promise.all([loadStats(), loadBookings()]); }
  catch (error) { $('#bookingRows').innerHTML = '<tr><td colspan="7" class="empty">Could not load bookings. Make sure the server is running.</td></tr>'; notify(error.message); }
}
$('#availabilityForm').addEventListener('submit', async (event) => {
  event.preventDefault(); resetAvailability();
  try {
    const params = parameters(); const result = await api('/availability?' + new URLSearchParams(params));
    state.checkedParameters = params;
    $('#quotePrice').textContent = money(result.price);
    $('#availabilityNote').textContent = result.slots.some(s => s.available) ? `Until ${result.endTime}. Select a free slot to continue.` : 'All five slots are taken for this time. Try another time.';
    $('#slots').innerHTML = result.slots.map(s => `<button type="button" class="slot" data-slot="${s.number}" ${s.available ? '' : 'disabled'}><strong>0${s.number}</strong><small>${s.available ? 'Available' : 'Booked'}</small></button>`).join('');
    $('#availabilityResult').classList.remove('hidden'); $('#confirmBooking').disabled = true;
  } catch (error) { notify(error.message); }
});
$('#slots').addEventListener('click', (event) => {
  const button = event.target.closest('[data-slot]'); if (!button || button.disabled) return;
  state.selectedSlot = Number(button.dataset.slot);
  document.querySelectorAll('.slot').forEach(slot => slot.classList.toggle('selected', slot === button));
  $('#confirmBooking').disabled = false;
});
$('#confirmBooking').addEventListener('click', async () => {
  const registration = $('#registration').value.trim();
  if (!registration) { notify('Enter the vehicle registration number.'); $('#registration').focus(); return; }
  if (!state.checkedParameters || !state.selectedSlot) return;
  const button = $('#confirmBooking'); button.disabled = true;
  try {
    const booking = await api('/bookings', { method: 'POST', body: JSON.stringify({ ...state.checkedParameters, registration, slot: state.selectedSlot }) });
    notify(`Booking #${booking.id} confirmed.`); resetAvailability(); $('#registration').value = ''; await refreshAll();
  } catch (error) { notify(error.message); button.disabled = false; }
});
$('#searchForm').addEventListener('submit', async event => { event.preventDefault(); try { await loadBookings(); } catch (error) { notify(error.message); } });
$('#clearSearch').addEventListener('click', async () => { $('#searchInput').value = ''; try { await loadBookings(); } catch (error) { notify(error.message); } });
$('#refresh').addEventListener('click', refreshAll);
function showReceipt(b) {
  const lines = [['Booking ID', '#' + b.id], ['Registration', b.car_number], ['Vehicle', vehicleLabel(b.vehicle_type)], ['Slot', b.slot], ['Start', b.start_time], ['End', b.end_time], ['Duration', b.hours + ' hour(s)'], ['Check-in', b.checked_in_at || '—'], ['Check-out', b.checked_out_at || '—'], ['Status', statusLabel(b.status)], ['Booking charge', money(b.price)]];
  $('#receiptContent').innerHTML = lines.map(([name,value]) => `<div class="receipt-line"><span>${escapeHtml(name)}</span><strong>${escapeHtml(value)}</strong></div>`).join('') + '<p class="rate-footnote">Booking charge only. No payment is processed through this application.</p>';
  $('#receiptDialog').showModal();
}
$('#bookingRows').addEventListener('click', async event => {
  const button = event.target.closest('[data-action]'); if (!button) return;
  const id = Number(button.dataset.id); const action = button.dataset.action;
  const booking = state.bookings.find(b => Number(b.id) === id);
  if (action === 'receipt') { showReceipt(booking); return; }
  try {
    let body;
    if (action === 'extend') {
      const answer = prompt(`Add how many hours? Total booking must stay at 5 hours or less. Current: ${booking.hours} hour(s).`);
      if (answer === null) return;
      const extraHours = Number(answer);
      if (!Number.isInteger(extraHours) || extraHours < 1 || extraHours + Number(booking.hours) > 5) { notify('Choose a valid number of extra hours.'); return; }
      // Calculate the revised estimate before asking for confirmation.
      const start = new Date(booking.start_time.replace(' ', 'T')); let newPrice = 0;
      for (let i = 0; i < Number(booking.hours) + extraHours; i++) {
        const hour = new Date(start.getTime() + i * 3600000).getHours(); const peak = hour >= 9 && hour < 18;
        newPrice += booking.vehicle_type === 'CAR' ? (peak ? 50 : 30) : (peak ? 30 : 20);
      }
      if (Number(booking.hours) + extraHours >= 4) newPrice *= 0.9;
      if (!confirm(`Extend by ${extraHours} hour(s)? Estimated new total: ${money(newPrice)}. Availability will be checked again.`)) return;
      body = JSON.stringify({ extraHours });
    } else if (!confirm(`${action.replace('-', ' ')} booking #${id}?`)) return;
    const result = await api(`/bookings/${id}/${action}`, { method: 'POST', body });
    notify(action === 'extend' ? `Extended. New charge: ${money(result.price)}.` : 'Booking updated.');
    await refreshAll();
  } catch (error) { notify(error.message); }
});
$('#closeReceipt').addEventListener('click', () => $('#receiptDialog').close());
$('#printReceipt').addEventListener('click', () => window.print());
document.querySelectorAll('.nav-link').forEach(link => link.addEventListener('click', () => {
  document.querySelectorAll('.nav-link').forEach(item => item.classList.remove('active')); link.classList.add('active');
}));
const nextHour = new Date(); nextHour.setHours(nextHour.getHours() + 1, 0, 0, 0);
const pad = n => String(n).padStart(2, '0');
$('#startTime').value = `${nextHour.getFullYear()}-${pad(nextHour.getMonth()+1)}-${pad(nextHour.getDate())}T${pad(nextHour.getHours())}:00`;

const isAdmin = () => state.user?.role === 'ADMIN';
const roleLabel = (value) => value === 'ADMIN' ? 'Administrator' : 'Staff';
function showAuth(setup) {
  state.user = null; state.setup = setup;
  document.querySelector('.app-shell').classList.add('hidden'); $('#authScreen').classList.remove('hidden');
  $('#authTitle').textContent = setup ? 'Create the administrator' : 'Sign in';
  $('#authNote').textContent = setup ? 'First run: this account will manage Parkwise and its users.' : 'Enter your username and password.';
  $('#authSubmit').textContent = setup ? 'Create account' : 'Sign in';
  $('#authNameRow').classList.toggle('hidden', !setup); $('#authName').required = setup;
  $('#authPassword').autocomplete = setup ? 'new-password' : 'current-password';
  $('#authPassword').value = ''; $('#authError').textContent = '';
}
function enterApp(user) {
  state.user = user;
  $('#authScreen').classList.add('hidden'); document.querySelector('.app-shell').classList.remove('hidden');
  $('#signedInName').textContent = user.full_name; $('#signedInRole').textContent = roleLabel(user.role);
  $('#avatar').textContent = user.full_name.trim()[0].toUpperCase();
  $('#usersLink').textContent = isAdmin() ? 'Users' : 'My account'; $('#usersTitle').textContent = isAdmin() ? 'Users' : 'My account';
  $('#usersNote').textContent = isAdmin() ? 'Add, edit and remove the people who can sign in.' : 'Change your name or password.';
  $('#newUser').classList.toggle('hidden', !isAdmin());
  closeUserForm(); refreshAll(); loadUsers();
}
$('#authForm').addEventListener('submit', async event => {
  event.preventDefault(); $('#authError').textContent = '';
  const button = $('#authSubmit'); button.disabled = true;
  const body = { username: $('#authUsername').value, password: $('#authPassword').value };
  if (state.setup) body.fullName = $('#authName').value;
  try {
    const user = await api(state.setup ? '/auth/setup' : '/auth/login', { method: 'POST', body: JSON.stringify(body) });
    $('#authForm').reset(); enterApp(user);
  } catch (error) { $('#authError').textContent = error.message; $('#authPassword').value = ''; }
  button.disabled = false;
});
$('#logout').addEventListener('click', async () => {
  try { await api('/auth/logout', { method: 'POST' }); showAuth(false); } catch (error) { notify(error.message); }
});
async function loadUsers() {
  try {
    state.users = await api('/users');
    $('#userRows').innerHTML = state.users.map(u => {
      const id = Number(u.id); const self = id === Number(state.user.id);
      const remove = isAdmin() && !self ? `<button class="action" data-user-action="delete" data-id="${id}">Delete</button>` : '';
      return `<tr><td><strong>${escapeHtml(u.username)}</strong>${self ? '<small>You</small>' : ''}</td><td>${escapeHtml(u.full_name)}</td><td>${escapeHtml(roleLabel(u.role))}</td><td>${escapeHtml(u.created_at)}</td><td><div class="actions"><button class="action" data-user-action="edit" data-id="${id}">Edit</button>${remove}</div></td></tr>`;
    }).join('');
  } catch (error) { notify(error.message); }
}
function closeUserForm() { state.editingUser = null; $('#userForm').reset(); $('#userForm').classList.add('hidden'); }
function openUserForm(user) {
  $('#userForm').reset(); state.editingUser = user; $('#userForm').classList.remove('hidden');
  const self = user && Number(user.id) === Number(state.user.id);
  $('#userFormTitle').textContent = user ? `Edit ${user.username}` : 'New user';
  $('#userUsernameRow').classList.toggle('hidden', !isAdmin()); $('#userRoleRow').classList.toggle('hidden', !isAdmin());
  $('#userCurrentRow').classList.toggle('hidden', !self);
  $('#userPasswordLabel').textContent = user ? 'New password (leave blank to keep)' : 'Password';
  $('#userUsername').value = user?.username || ''; $('#userFullName').value = user?.full_name || ''; $('#userRole').value = user?.role || 'STAFF';
  $('#userFullName').focus();
}
$('#newUser').addEventListener('click', () => openUserForm(null));
$('#cancelUser').addEventListener('click', closeUserForm);
$('#userForm').addEventListener('submit', async event => {
  event.preventDefault();
  const editing = state.editingUser;
  const body = { username: $('#userUsername').value, fullName: $('#userFullName').value, role: $('#userRole').value, password: $('#userPassword').value, currentPassword: $('#userCurrent').value };
  try {
    const saved = await api(editing ? `/users/${Number(editing.id)}` : '/users', { method: editing ? 'PUT' : 'POST', body: JSON.stringify(body) });
    notify(editing ? 'User updated.' : 'User added.'); closeUserForm();
    if (Number(saved.id) === Number(state.user.id)) enterApp(saved); else await loadUsers();
  } catch (error) { notify(error.message); }
});
$('#userRows').addEventListener('click', async event => {
  const button = event.target.closest('[data-user-action]'); if (!button) return;
  const id = Number(button.dataset.id); const user = state.users.find(u => Number(u.id) === id);
  if (button.dataset.userAction === 'edit') { openUserForm(user); return; }
  if (!confirm(`Delete ${user.username}? They will no longer be able to sign in.`)) return;
  try { await api(`/users/${id}`, { method: 'DELETE' }); notify('User deleted.'); closeUserForm(); await loadUsers(); } catch (error) { notify(error.message); }
});
(async () => {
  try {
    const status = await api('/auth/status');
    if (status.user) enterApp(status.user); else showAuth(status.setupNeeded);
  } catch (error) { showAuth(false); $('#authError').textContent = error.message; }
})();
