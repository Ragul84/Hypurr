import { BrowserRouter, Routes, Route, Link } from 'react-router-dom';
import Home from './Home';
import Orders from './pages/Orders';
import OrderDetail from './OrderDetail';

export default function App() {
  return (
    <BrowserRouter>
      <nav><Link to="/">Home</Link> <Link to="/orders">Orders</Link></nav>
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/orders" element={<Orders />} />
        <Route path="/orders/:id" element={<OrderDetail />} />
      </Routes>
    </BrowserRouter>
  );
}
