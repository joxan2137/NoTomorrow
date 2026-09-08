/** Exact-code nutrition grounding. OFF is community maintained; the package label remains authoritative. */
export function validBarcode(code: string): boolean {
  if (!/^(?:\d{8}|\d{12}|\d{13}|\d{14})$/.test(code)) return false;
  const digits = [...code].map(Number);
  const check = digits.pop();
  const sum = digits.reverse().reduce((total, n, index) => total + n * (index % 2 === 0 ? 3 : 1), 0);
  return (10 - sum % 10) % 10 === check;
}
export interface Nutrition { kcal: number; protein: number; carbs: number; fat: number }
export function nutritionFromProduct(product: unknown): Nutrition | null {
  if (!product || typeof product !== 'object') return null;
  const n = (product as Record<string, unknown>).nutriments as Record<string, unknown> | undefined;
  if (!n) return null;
  const read = (key: string): number | null => {
    const raw = n[key];
    if (typeof raw !== 'string' && typeof raw !== 'number') return null;
    if (String(raw).trim() === '') return null;
    const v = Number(String(raw).replace(',', '.'));
    return Number.isFinite(v) && v >= 0 ? v : null;
  };
  const kj = read('energy-kj_100g') ?? read('energy_100g');
  const kcal = read('energy-kcal_100g') ?? (kj === null ? null : kj / 4.184);
  const protein = read('proteins_100g'), carbs = read('carbohydrates_100g'), fat = read('fat_100g');
  if (kcal === null || protein === null || carbs === null || fat === null || kcal > 950 || protein + carbs + fat > 105) return null;
  return { kcal, protein, carbs, fat };
}
export async function lookupNutrition(code: string, fetchImpl: typeof fetch = fetch): Promise<Nutrition | null> {
  if (!validBarcode(code)) return null;
  if (code.length === 13 && code.startsWith('2')) return null;
  try {
    const response = await fetchImpl(`https://world.openfoodfacts.org/api/v2/product/${code}.json?fields=code,nutriments`, {
      headers: { 'User-Agent': 'NoTomorrow/0.1 (markzaluben@proton.me)', Accept: 'application/json' },
      signal: AbortSignal.timeout(6000),
    });
    if (!response.ok) return null;
    const body = await response.json() as { status?: number; product?: { code?: string } };
    if (body.status !== 1 || String(body.product?.code).replace(/^0+/, '') !== code.replace(/^0+/, '')) return null;
    return nutritionFromProduct(body.product);
  } catch { return null; }
}
