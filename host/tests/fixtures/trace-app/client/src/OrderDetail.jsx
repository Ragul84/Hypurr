import { useParams } from 'react-router-dom';
import axios from 'axios';

// AI-built: a page with its own API call inlined.
export default function OrderDetail() {
  const { id } = useParams();
  const cancel = () => axios.delete(`/api/orders/${id}`);
  return (
    <main>
      <h1>Order {id}</h1>
      <form onSubmit={cancel}>
        <button type="submit">Cancel order</button>
      </form>
    </main>
  );
}
