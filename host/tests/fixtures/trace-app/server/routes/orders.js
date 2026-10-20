const router = require('express').Router();

const all = [];

router.get('/orders', (req, res) => res.json(all));

router.post('/orders', function placeOrder(req, res) {
  const order = { id: all.length + 1, ...req.body };
  all.push(order);
  res.status(201).json(order);
});

router.delete('/orders/:id', (req, res) => res.status(204).end());

module.exports = router;
