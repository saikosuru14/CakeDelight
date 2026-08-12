/*
 * Cake Delight - browser client.
 *
 * Vanilla JS, no framework and no build step. Every request goes to the same origin under
 * /api/, which nginx reverse-proxies to the API Gateway. That is why there is no CORS
 * configuration anywhere in this project.
 *
 * The only persisted state is the customer id, in localStorage, so a page reload keeps the
 * basket context.
 */
'use strict';

const API = '/api';
const CUSTOMER_KEY = 'cakeDelight.customerId';

/* Extra guidance for the error codes that matter in the demo flow. The service message is
   always shown; these only add context. */
const ERROR_HINTS = {
  CAKE_UNAVAILABLE: 'That cake is marked unavailable, so nothing was added to your basket.',
  INVALID_PRICE_RANGE: 'The minimum price must not be greater than the maximum price.',
  BASKET_EMPTY: 'Add at least one cake before placing an order.',
  VALIDATION_ERROR: 'Check the highlighted values and try again.',
  CATALOG_UNAVAILABLE: 'The Catalog Service is not answering right now. Try again in a moment.',
  SERVICE_UNAVAILABLE: 'The gateway could not reach that service. Try again in a moment.'
};

const el = (id) => document.getElementById(id);

/* ---------------------------------------------------------------- HTTP */

class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function api(path, options = {}) {
  const init = { headers: { Accept: 'application/json' }, ...options };
  if (init.body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(init.body);
  }

  let response;
  try {
    response = await fetch(API + path, init);
  } catch (networkError) {
    throw new ApiError(0, 'NETWORK_ERROR', 'Could not reach the API. Is the stack running?');
  }

  const text = await response.text();
  let payload = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch (parseError) {
      payload = null;
    }
  }

  if (!response.ok) {
    const code = (payload && payload.code) || 'HTTP_' + response.status;
    const message = (payload && payload.message) || 'Request failed with status ' + response.status + '.';
    throw new ApiError(response.status, code, message);
  }
  return payload;
}

/* ---------------------------------------------------------------- feedback */

function showError(error) {
  const code = error instanceof ApiError ? error.code : 'UNEXPECTED_ERROR';
  const hint = ERROR_HINTS[code];
  el('errorCode').textContent = code;
  el('errorMessage').textContent = error.message + (hint ? ' ' + hint : '');
  const banner = el('errorBanner');
  banner.hidden = false;
  el('successBanner').hidden = true;
  // Banners render at the top of the page while the actions that trigger them (Place order)
  // sit in the right-hand column, so the message can be off-screen when it appears.
  banner.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function showSuccess(message) {
  el('successMessage').textContent = message;
  const banner = el('successBanner');
  banner.hidden = false;
  el('errorBanner').hidden = true;
  banner.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function clearBanners() {
  el('errorBanner').hidden = true;
  el('successBanner').hidden = true;
}

/* ---------------------------------------------------------------- formatting */

const money = (value) => (value === null || value === undefined ? '-' : Number(value).toFixed(2));
const currency = (value) => '\u00A3' + money(value);

function formatInstant(value) {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/* ---------------------------------------------------------------- customer id */

function customerId() {
  return el('customerId').value.trim();
}

function requireCustomerId() {
  const id = customerId();
  if (!id) {
    showError(new ApiError(0, 'VALIDATION_ERROR', 'A customer ID is required.'));
    el('customerId').focus();
    return null;
  }
  return id;
}

/* ---------------------------------------------------------------- catalogue */

let knownCategories = null;

async function loadCakes() {
  const params = new URLSearchParams();
  const name = el('filterName').value.trim();
  const category = el('filterCategory').value;
  const minPrice = el('filterMinPrice').value.trim();
  const maxPrice = el('filterMaxPrice').value.trim();
  if (name) params.set('name', name);
  if (category) params.set('category', category);
  if (minPrice) params.set('minPrice', minPrice);
  if (maxPrice) params.set('maxPrice', maxPrice);
  params.set('page', '0');
  params.set('size', '50');

  const catalog = el('catalog');
  catalog.setAttribute('aria-busy', 'true');
  el('resultCount').textContent = 'Loading\u2026';

  try {
    const page = await api('/cakes?' + params.toString());
    const cakes = page.content || [];
    renderCakes(cakes);
    el('resultCount').textContent = page.totalElements + (page.totalElements === 1 ? ' cake' : ' cakes');
    if (!knownCategories) {
      knownCategories = true;
      populateCategories(cakes);
    }
    cakes.forEach(loadAverageRating);
  } catch (error) {
    el('resultCount').textContent = 'Could not load the catalogue';
    renderCakes([]);
    showError(error);
  } finally {
    catalog.setAttribute('aria-busy', 'false');
  }
}

function populateCategories(cakes) {
  const select = el('filterCategory');
  const categories = [...new Set(cakes.map((cake) => cake.category).filter(Boolean))].sort();
  categories.forEach((category) => {
    const option = document.createElement('option');
    option.value = category;
    option.textContent = category;
    select.appendChild(option);
  });
}

function renderCakes(cakes) {
  const grid = el('cakeGrid');
  const template = el('cakeCardTemplate');
  grid.textContent = '';
  el('catalogEmpty').hidden = cakes.length > 0;

  cakes.forEach((cake) => {
    const card = template.content.firstElementChild.cloneNode(true);
    card.dataset.cakeId = cake.id;
    card.dataset.cakeName = cake.name;
    if (!cake.available) card.classList.add('cake-unavailable');

    card.querySelector('.cake-name').textContent = cake.name;
    card.querySelector('.cake-price').textContent = currency(cake.price);
    card.querySelector('.cake-description').textContent = cake.description || 'No description available.';
    card.querySelector('.pill-category').textContent = cake.category || 'Uncategorised';

    const availability = card.querySelector('.pill-availability');
    availability.textContent = cake.available ? 'Available' : 'Unavailable';
    availability.classList.add(cake.available ? 'pill-available' : 'pill-unavailable');

    card.querySelector('[data-role="rating"]').textContent = 'Rating: \u2026';

    const image = card.querySelector('.cake-image');
    image.alt = cake.name + (cake.category ? ', ' + cake.category : '');
    image.src = cake.imageUrl || '';
    // The seeded image URLs point at a placeholder CDN host, so a failed load is expected
    // rather than exceptional. Swap in a labelled fallback instead of a broken image icon.
    image.addEventListener('error', () => {
      const figure = image.parentElement;
      figure.textContent = '';
      const fallback = document.createElement('span');
      fallback.className = 'cake-fallback';
      fallback.setAttribute('role', 'img');
      fallback.setAttribute('aria-label', 'No photo available for ' + cake.name);
      fallback.textContent = '\uD83E\uDDC1';
      figure.appendChild(fallback);
    });

    // Labels are tied to their controls with for/id, so the ids have to be unique per card.
    const qtyInput = card.querySelector('.qty-input');
    const qtyLabel = card.querySelector('.qty-label');
    qtyInput.id = 'qty-' + cake.id;
    qtyLabel.setAttribute('for', qtyInput.id);

    const scoreInput = card.querySelector('.score-input');
    const scoreLabel = card.querySelector('.score-label');
    scoreInput.id = 'score-' + cake.id;
    scoreLabel.setAttribute('for', scoreInput.id);

    card.querySelector('[data-action="add"]').setAttribute('aria-label', 'Add ' + cake.name + ' to basket');
    card.querySelector('[data-action="rate"]').setAttribute('aria-label', 'Submit a rating for ' + cake.name);

    grid.appendChild(card);
  });
}

async function loadAverageRating(cake) {
  const target = document.querySelector('.cake-card[data-cake-id="' + cake.id + '"] [data-role="rating"]');
  if (!target) return;
  try {
    const average = await api('/cakes/' + cake.id + '/ratings/average');
    target.textContent = average.ratingCount > 0
      ? 'Rating: ' + Number(average.averageRating).toFixed(1) + '/5 (' + average.ratingCount + ')'
      : 'Not yet rated';
  } catch (error) {
    target.textContent = 'Rating unavailable';
  }
}

/* ---------------------------------------------------------------- basket */

function renderBasket(basket) {
  const body = el('basketBody');
  body.textContent = '';
  const items = (basket && basket.items) || [];

  if (items.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'empty-state';
    empty.textContent = 'Your basket is empty.';
    body.appendChild(empty);
    el('basketTotalRow').hidden = true;
    return;
  }

  items.forEach((item) => {
    const line = document.createElement('div');
    line.className = 'basket-line';
    line.dataset.cakeId = item.cakeId;

    const name = document.createElement('p');
    name.className = 'basket-line-name';
    name.textContent = item.cakeName;

    const lineTotal = document.createElement('p');
    lineTotal.className = 'basket-line-total';
    lineTotal.textContent = currency(item.lineTotal);

    const unit = document.createElement('p');
    unit.className = 'basket-line-unit';
    unit.textContent = currency(item.unitPrice) + ' each';

    const controls = document.createElement('div');
    controls.className = 'basket-line-controls';

    const qtyId = 'basket-qty-' + item.cakeId;
    const qtyLabel = document.createElement('label');
    qtyLabel.setAttribute('for', qtyId);
    qtyLabel.textContent = 'Qty';

    const qtyInput = document.createElement('input');
    qtyInput.id = qtyId;
    qtyInput.className = 'qty-input';
    qtyInput.type = 'number';
    qtyInput.min = '1';
    qtyInput.step = '1';
    qtyInput.value = String(item.quantity);

    const updateButton = document.createElement('button');
    updateButton.type = 'button';
    updateButton.className = 'btn btn-secondary btn-small';
    updateButton.dataset.action = 'update';
    updateButton.textContent = 'Update';
    updateButton.setAttribute('aria-label', 'Update the quantity of ' + item.cakeName);

    const removeButton = document.createElement('button');
    removeButton.type = 'button';
    removeButton.className = 'btn btn-danger btn-small';
    removeButton.dataset.action = 'remove';
    removeButton.textContent = 'Remove';
    removeButton.setAttribute('aria-label', 'Remove ' + item.cakeName + ' from the basket');

    controls.append(qtyLabel, qtyInput, updateButton, removeButton);
    line.append(name, lineTotal, unit, document.createElement('span'), controls);
    body.appendChild(line);
  });

  el('basketTotal').textContent = currency(basket.basketTotal);
  el('basketTotalRow').hidden = false;
}

async function loadBasket() {
  const id = customerId();
  if (!id) return;
  try {
    renderBasket(await api('/baskets/' + encodeURIComponent(id)));
  } catch (error) {
    showError(error);
  }
}

async function addToBasket(cakeId, cakeName, quantity) {
  const id = requireCustomerId();
  if (!id) return;
  try {
    const basket = await api('/baskets/' + encodeURIComponent(id) + '/items', {
      method: 'POST',
      body: { cakeId, quantity }
    });
    renderBasket(basket);
    showSuccess(quantity + ' \u00D7 ' + cakeName + ' added to your basket.');
  } catch (error) {
    showError(error);
  }
}

async function updateLine(cakeId, quantity) {
  const id = requireCustomerId();
  if (!id) return;
  try {
    const basket = await api('/baskets/' + encodeURIComponent(id) + '/items/' + cakeId, {
      method: 'PUT',
      body: { quantity }
    });
    renderBasket(basket);
    showSuccess('Quantity updated.');
  } catch (error) {
    showError(error);
  }
}

async function removeLine(cakeId) {
  const id = requireCustomerId();
  if (!id) return;
  try {
    const basket = await api('/baskets/' + encodeURIComponent(id) + '/items/' + cakeId, { method: 'DELETE' });
    renderBasket(basket);
    showSuccess('Item removed from your basket.');
  } catch (error) {
    showError(error);
  }
}

/* ---------------------------------------------------------------- checkout */

let lastOrderId = null;

async function checkout() {
  const id = requireCustomerId();
  if (!id) return;
  const customerEmail = el('customerEmail').value.trim();
  try {
    const order = await api('/orders', { method: 'POST', body: { customerId: id, customerEmail } });
    lastOrderId = order.orderId;
    el('orderId').textContent = order.orderId;
    el('orderTotal').textContent = currency(order.orderTotal);
    el('orderStatus').textContent = order.status;
    el('orderResult').hidden = false;
    el('notificationsPanel').hidden = true;
    el('notificationsBody').textContent = '';
    showSuccess('Order ' + order.orderId + ' placed for ' + currency(order.orderTotal) + '.');
    await loadBasket();
  } catch (error) {
    showError(error);
  }
}

/* ---------------------------------------------------------------- notifications */

async function loadNotifications() {
  if (!lastOrderId) return;
  const body = el('notificationsBody');
  el('notificationsPanel').hidden = false;
  body.textContent = '';

  try {
    const records = await api('/notifications/orders/' + lastOrderId);
    if (!records || records.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'empty-state';
      empty.textContent = 'No notification recorded yet. The order.completed event is delivered '
        + 'asynchronously, so the notification may still be in flight; try again in a moment.';
      body.appendChild(empty);
      return;
    }

    const table = document.createElement('table');
    table.className = 'notify-table';
    const caption = document.createElement('caption');
    caption.className = 'hint';
    caption.textContent = 'Delivery attempts for order ' + lastOrderId;
    table.appendChild(caption);

    const head = table.createTHead().insertRow();
    ['Channel', 'Status', 'Attempted at'].forEach((title) => {
      const th = document.createElement('th');
      th.scope = 'col';
      th.textContent = title;
      head.appendChild(th);
    });

    const tbody = table.createTBody();
    records.forEach((record) => {
      const row = tbody.insertRow();
      row.insertCell().textContent = record.channel;
      const status = row.insertCell();
      status.textContent = record.status;
      status.className = record.status === 'SENT' ? 'status-sent' : 'status-failed';
      row.insertCell().textContent = formatInstant(record.attemptedAt);
    });

    body.appendChild(table);
    showSuccess('Notification record loaded: the order event reached the Notification Service.');
  } catch (error) {
    showError(error);
  }
}

/* ---------------------------------------------------------------- ratings */

async function submitRating(cakeId, cakeName, score) {
  const id = requireCustomerId();
  if (!id) return;
  try {
    await api('/cakes/' + cakeId + '/ratings', { method: 'POST', body: { customerId: id, score } });
    await loadAverageRating({ id: cakeId });
    showSuccess('Thanks - your ' + score + '/5 rating for ' + cakeName + ' was recorded.');
  } catch (error) {
    showError(error);
  }
}

/* ---------------------------------------------------------------- wiring */

function readQuantity(input) {
  const value = Number.parseInt(input.value, 10);
  return Number.isNaN(value) ? 0 : value;
}

function init() {
  const stored = localStorage.getItem(CUSTOMER_KEY);
  if (stored) el('customerId').value = stored;

  el('customerId').addEventListener('change', () => {
    localStorage.setItem(CUSTOMER_KEY, customerId());
    loadBasket();
  });

  document.querySelectorAll('.banner-close').forEach((button) => {
    button.addEventListener('click', () => {
      el(button.dataset.dismiss).hidden = true;
    });
  });

  el('filterForm').addEventListener('submit', (event) => {
    event.preventDefault();
    clearBanners();
    loadCakes();
  });

  el('resetFilters').addEventListener('click', () => {
    // Let the native reset clear the fields first, then reload the unfiltered catalogue.
    setTimeout(() => {
      clearBanners();
      loadCakes();
    }, 0);
  });

  el('cakeGrid').addEventListener('click', (event) => {
    const button = event.target.closest('button[data-action]');
    if (!button) return;
    const card = button.closest('.cake-card');
    const cakeId = card.dataset.cakeId;
    const cakeName = card.dataset.cakeName;
    clearBanners();

    if (button.dataset.action === 'add') {
      addToBasket(cakeId, cakeName, readQuantity(card.querySelector('.qty-input')));
    } else {
      submitRating(cakeId, cakeName, Number.parseInt(card.querySelector('.score-input').value, 10));
    }
  });

  el('basketBody').addEventListener('click', (event) => {
    const button = event.target.closest('button[data-action]');
    if (!button) return;
    const line = button.closest('.basket-line');
    const cakeId = line.dataset.cakeId;
    clearBanners();

    if (button.dataset.action === 'update') {
      updateLine(cakeId, readQuantity(line.querySelector('.qty-input')));
    } else {
      removeLine(cakeId);
    }
  });

  el('refreshBasket').addEventListener('click', () => {
    clearBanners();
    loadBasket();
  });

  el('checkoutForm').addEventListener('submit', (event) => {
    event.preventDefault();
    clearBanners();
    checkout();
  });

  el('loadNotifications').addEventListener('click', () => {
    clearBanners();
    loadNotifications();
  });

  loadCakes();
  loadBasket();
}

document.addEventListener('DOMContentLoaded', init);
