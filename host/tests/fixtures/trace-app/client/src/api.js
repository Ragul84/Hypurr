const BASE = '/api';

export async function listProducts() {
  const res = await fetch(`${BASE}/products`);
  return res.json();
}

export async function createOrder(body) {
  const res = await fetch('/api/orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return res.json();
}

export async function listOrders() {
  const res = await fetch('/api/orders');
  return res.json();
}
