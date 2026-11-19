-- Atrion : passeur de l'ile des Wabbits, raccourci entre le port et les entrees des donjons.
-- Present au port [24,-2], devant le Chateau du Wa [24,-13] et pres du Terrier du Wa [28,-12].
local npc = Npc(99003, 90)
npc.colors = {0x3A6EA5, 0xF2E6C9, 0xC0392B}
npc.accessories = {0, 0, 0, 0, 0}

local DESTS = {
    [99020] = {map = 822, cell = 320},  -- Chateau du Wa
    [99021] = {map = 1763, cell = 330}, -- Terrier du Wa
    [99022] = {map = 833, cell = 141},  -- Port (arrivee du bateau)
}

local function answers(p)
    local list = {}
    local here = p:mapID()
    for _, id in ipairs({99020, 99021, 99022}) do
        if DESTS[id].map ~= here then table.insert(list, id) end
    end
    table.insert(list, 99003)
    return list
end

---@param p Player
---@param answer number
function npc:onTalk(p, answer)
    if answer == 0 then
        p:ask(99004, answers(p))
    elseif DESTS[answer] then
        p:endDialog()
        p:teleport(DESTS[answer].map, DESTS[answer].cell)
    else
        p:endDialog()
    end
end

RegisterNPCDef(npc)
