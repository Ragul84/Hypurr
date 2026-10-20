const express = require('express');
const orders = require('./routes/orders');

const app = express();
app.use(express.json());
app.use('/api', orders);

app.get('/api/products', (req, res) => {
  res.json([{ id: 1, name: 'Red socks' }]);
});

app.listen(3001);
