-- Atrion : marchand de la boutique (points boutique -> potions, parchemins, cosmetiques).
-- Genere depuis la liste de la boutique. Reponses : 99030+ = categories, 99100+n = article n+1 (voir Atrion.SHOP),
-- 99029 = retour, 99003 = au revoir. Les textes sont fournis par le client Atrion.
local npc = Npc(99002, 30)
npc.colors = {0x8B5A2B, 0xF2D16B, 0x2E7D32}
npc.accessories = {0, 0, 0, 0, 0}

local CATEGORIES = {99030, 99031, 99032, 99033, 99034, 99035, 99036, 99037, 99003}
local CATEGORY_ITEMS = {
    [99030] = {99100, 99101},
    [99031] = {99102, 99103, 99104, 99105, 99106, 99107, 99108},
    [99032] = {99109, 99110, 99111, 99112, 99113, 99114, 99115, 99116, 99117, 99118, 99119, 99120, 99121, 99122},
    [99033] = {99123, 99124, 99125, 99126, 99127, 99128, 99129, 99130, 99131, 99132, 99133},
    [99034] = {99134, 99135, 99136, 99137, 99138, 99139, 99140, 99141},
    [99035] = {99142, 99143, 99144, 99145, 99146, 99147, 99148, 99149, 99150},
    [99036] = {99151, 99152, 99153, 99154, 99155, 99156, 99157, 99158, 99159, 99160, 99161, 99162, 99163, 99164},
    [99037] = {99165, 99166, 99167, 99168, 99169, 99170, 99171, 99172, 99173, 99174, 99175, 99176, 99177, 99178},
}

local function showCategory(p, cat)
    local list = {}
    for _, a in ipairs(CATEGORY_ITEMS[cat]) do table.insert(list, a) end
    table.insert(list, 99029)
    table.insert(list, 99003)
    p:ask(99005, list)
end

local function categoryOf(answer)
    for cat, items in pairs(CATEGORY_ITEMS) do
        for _, a in ipairs(items) do if a == answer then return cat end end
    end
    return nil
end

---@param p Player
---@param answer number
function npc:onTalk(p, answer)
    if answer == 0 or answer == 99029 then
        if answer == 0 then p:atrion(".points") end
        p:ask(99003, CATEGORIES)
    elseif CATEGORY_ITEMS[answer] then
        showCategory(p, answer)
    elseif answer >= 99100 and answer < 99179 then
        p:atrion(".acheter " .. (answer - 99099))
        showCategory(p, categoryOf(answer))
    else
        p:endDialog()
    end
end

RegisterNPCDef(npc)
