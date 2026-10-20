export default function OrderRow({ order }) {
  return <p>{order.sku} × {order.qty}</p>;
}
