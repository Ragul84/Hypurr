import { useState } from 'react';
import { createOrder, listOrders } from '../api';
import OrderRow from '../components/OrderRow';

export default function Orders() {
  const [orders, setOrders] = useState([]);
  const [done, setDone] = useState('');

  async function handlePlaceOrder() {
    const order = await createOrder({ sku: 'SOCK-RED', qty: 2 });
    setDone(`Order ${order.id} placed`);
  }

  const handleRefresh = async () => {
    setOrders(await listOrders());
  };

  return (
    <main>
      <h1>Orders</h1>
      <button onClick={handlePlaceOrder}>Place order</button>
      <button onClick={handleRefresh}>Refresh</button>
      <p>{done}</p>
      {orders.map((o) => <OrderRow key={o.id} order={o} />)}
    </main>
  );
}
