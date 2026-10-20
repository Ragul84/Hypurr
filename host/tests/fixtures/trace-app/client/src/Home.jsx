import { useEffect, useState } from 'react';
import { listProducts } from './api';

export default function Home() {
  const [items, setItems] = useState([]);
  useEffect(() => { listProducts().then(setItems); }, []);
  return (
    <main>
      <h1>Sock Shop</h1>
      <input name="search" placeholder="Search socks" />
      {items.map((p) => <p key={p.id}>{p.name}</p>)}
    </main>
  );
}
