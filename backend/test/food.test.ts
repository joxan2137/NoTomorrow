import { describe, expect, it, vi } from 'vitest';
import { validBarcode, nutritionFromProduct, lookupNutrition } from '../src/food.js';

describe('exact Polish retail nutrition lookup', () => {
  it('checks EAN digits and check digit without assuming 590 is required', () => {
    expect(validBarcode('5901234123457')).toBe(true);
    expect(validBarcode('4006381333931')).toBe(true);
    expect(validBarcode('5901234123456')).toBe(false);
    expect(validBarcode('https://example.com/5901234123457')).toBe(false);
  });
  it('converts kJ and Polish decimal commas, retaining genuine zero values', () => {
    const result = nutritionFromProduct({ nutriments: { energy_100g: '418,4', proteins_100g: '10', carbohydrates_100g: '15', fat_100g: 0 } });
    expect(result).toMatchObject({ protein: 10, carbs: 15, fat: 0 });
    expect(result?.kcal).toBeCloseTo(100);
  });
  it('never turns missing nutrients or non-finite numbers into grounded zeros', () => {
    expect(nutritionFromProduct({ nutriments: { 'energy-kcal_100g': 100 } })).toBeNull();
    expect(nutritionFromProduct({ nutriments: { 'energy-kcal_100g': 'NaN', proteins_100g: 0, carbohydrates_100g: 0, fat_100g: 0 } })).toBeNull();
  });
  it('does not call OFF for an invented or incomplete code', async () => {
    const fetcher = vi.fn();
    expect(await lookupNutrition('5901234123456', fetcher)).toBeNull();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it('rejects a mismatched product and tolerates catalog/network failures', async () => {
    const wrong = vi.fn(async () => new Response(JSON.stringify({ status: 1, product: { code: '4006381333931' } })));
    expect(await lookupNutrition('5901234123457', wrong)).toBeNull();
    expect(await lookupNutrition('5901234123457', async () => { throw new Error('offline'); })).toBeNull();
  });
});
