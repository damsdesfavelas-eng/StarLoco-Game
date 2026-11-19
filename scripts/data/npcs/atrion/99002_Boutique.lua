-- Atrion : marchand de la boutique (points boutique -> parchemins).
-- Reponses 99010 a 99016 = articles 1 a 7 de la boutique (voir Atrion.SHOP), 99003 = au revoir.
local npc = Npc(99002, 30)
npc.colors = {0x8B5A2B, 0xF2D16B, 0x2E7D32}
npc.accessories = {0, 0, 0, 0, 0}

local ITEMS = {99010, 99011, 99012, 99013, 99014, 99015, 99016, 99003}

---@param p Player
---@param answer number
function npc:onTalk(p, answer)
    if answer == 0 then
        p:atrion(".points")
        p:ask(99003, ITEMS)
    elseif answer >= 99010 and answer <= 99016 then
        p:atrion(".acheter " .. (answer - 99009))
        p:ask(99003, ITEMS)
    else
        p:endDialog()
    end
end

RegisterNPCDef(npc)
