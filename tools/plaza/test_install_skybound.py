import unittest

import numpy as np

from install_skybound import pack, unpack, block_state, state_name


class RegionEncodingTests(unittest.TestCase):
    def test_known_packing_order(self):
        self.assertEqual(int(pack([1, 2, 3], 4)[0]), 0x321)

    def test_values_do_not_cross_long_boundary(self):
        values = np.arange(4096) % 31
        encoded = pack(values, 5)
        self.assertEqual(len(encoded), 342)
        self.assertEqual((int(encoded[0]) >> 60) & 15, 0)
        np.testing.assert_array_equal(unpack(encoded, 5, 4096), values)

    def test_signed_long_with_high_bit(self):
        values = [15] * 16
        self.assertEqual(int(pack(values, 4)[0]), -1)
        np.testing.assert_array_equal(unpack(pack(values, 4), 4, 16), values)

    def test_block_properties_survive(self):
        name = 'minecraft:spruce_stairs[facing=east,half=top,shape=straight,waterlogged=false]'
        self.assertEqual(state_name(block_state(name)), name)


if __name__ == '__main__':
    unittest.main()
