-- Atrion : Ganymede, gardien du prestige (zaap d'Astrub).
-- Les textes (nom, questions 99001-99002, reponses 99001-99004) sont ajoutes par le client Atrion.
local npc = Npc(99001, 50)
npc.colors = {0xF0F0F0, 0x2F5DA8, 0xD4AF37}
npc.accessories = {0, 0, 0, 0, 0}

local MAIN = {99001, 99002, 99003}

---@param p Player
---@param answer number
function npc:onTalk(p, answer)
    if answer == 0 then
        p:ask(99001, MAIN)
    elseif answer == 99001 then
        p:ask(99002, {99004, 99003})
    elseif answer == 99002 then
        p:atrion(".prestige")
        p:ask(99001, MAIN)
    elseif answer == 99004 then
        p:endDialog()
        p:atrion(".prestige confirmer")
    else
        p:endDialog()
    end
end

RegisterNPCDef(npc)
